#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <time.h>
#include <unistd.h>

#define FUSE_SUPER_MAGIC 0x65735546
#define CACHE_SLOTS 32
#define CACHE_TTL_NS 2000000000ULL

struct listing {
    dev_t dev;
    ino_t ino;
    struct timespec mtime;
    struct timespec ctime;
    uint64_t expires;
    size_t count;
    unsigned int readers;
    struct dirent64 *entries;
};

struct reader {
    DIR *dir;
    struct listing *listing;
    size_t pos;
    struct reader *next;
};

static struct listing listings[CACHE_SLOTS];
static struct reader *readers;
static pthread_mutex_t cache_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_once_t hooks_once = PTHREAD_ONCE_INIT;
static struct dirent64 *(*real_readdir64)(DIR *);
static int (*real_closedir)(DIR *);
static void (*real_rewinddir)(DIR *);
static long (*real_telldir)(DIR *);
static void (*real_seekdir)(DIR *, long);

static void lock_for_fork(void) { pthread_mutex_lock(&cache_lock); }
static void unlock_after_fork(void) { pthread_mutex_unlock(&cache_lock); }

static void init_hooks(void) {
    real_readdir64 = dlsym(RTLD_NEXT, "readdir64");
    real_closedir = dlsym(RTLD_NEXT, "closedir");
    real_rewinddir = dlsym(RTLD_NEXT, "rewinddir");
    real_telldir = dlsym(RTLD_NEXT, "telldir");
    real_seekdir = dlsym(RTLD_NEXT, "seekdir");
    pthread_atfork(lock_for_fork, unlock_after_fork, unlock_after_fork);
}

static int is_steam_runtime(void) {
    return !strcmp(program_invocation_short_name, "steam") &&
           strstr(program_invocation_name, "/Steam/steamrtarm64/") != NULL;
}

static uint64_t monotonic_ns(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (uint64_t)now.tv_sec * 1000000000ULL + now.tv_nsec;
}

static int same_directory(const struct listing *listing, const struct stat *st, uint64_t now) {
    return listing->entries && listing->expires > now && listing->dev == st->st_dev &&
           listing->ino == st->st_ino && listing->mtime.tv_sec == st->st_mtim.tv_sec &&
           listing->mtime.tv_nsec == st->st_mtim.tv_nsec &&
           listing->ctime.tv_sec == st->st_ctim.tv_sec &&
           listing->ctime.tv_nsec == st->st_ctim.tv_nsec;
}

static struct reader *find_reader(DIR *dir) {
    for (struct reader *reader = readers; reader; reader = reader->next)
        if (reader->dir == dir) return reader;
    return NULL;
}

static void release_reader(DIR *dir) {
    struct reader **link = &readers;
    while (*link) {
        if ((*link)->dir == dir) {
            struct reader *reader = *link;
            reader->listing->readers--;
            *link = reader->next;
            free(reader);
            return;
        }
        link = &(*link)->next;
    }
}

static struct listing *available_listing(void) {
    struct listing *oldest = NULL;
    for (size_t i = 0; i < CACHE_SLOTS; i++) {
        struct listing *listing = &listings[i];
        if (listing->readers == 0 && (!oldest || listing->expires < oldest->expires))
            oldest = listing;
    }
    return oldest;
}

/* Capture a directory only for Steam's FUSE-backed library. The short lifetime is a fallback
 * for filesystems that fail to update directory timestamps; normal create/remove operations
 * invalidate the snapshot as soon as fstat observes their new mtime or ctime. */
static struct listing *listing_for(DIR *dir) {
    int fd = dirfd(dir);
    struct statfs fs;
    struct stat before;
    if (!real_telldir || !real_rewinddir || real_telldir(dir) != 0 || fd < 0 ||
        fstatfs(fd, &fs) != 0 || fs.f_type != FUSE_SUPER_MAGIC || fstat(fd, &before) != 0)
        return NULL;

    uint64_t now = monotonic_ns();
    for (size_t i = 0; i < CACHE_SLOTS; i++)
        if (same_directory(&listings[i], &before, now)) return &listings[i];

    struct listing *slot = available_listing();
    if (!slot) return NULL;

    size_t capacity = 64;
    struct dirent64 *entries = malloc(capacity * sizeof(*entries));
    if (!entries) return NULL;
    size_t count = 0;
    int error = 0;
    for (;;) {
        if (count == capacity) {
            if (capacity > SIZE_MAX / 2 / sizeof(*entries)) {
                error = ENOMEM;
                break;
            }
            size_t next_capacity = capacity * 2;
            struct dirent64 *grown = realloc(entries, next_capacity * sizeof(*entries));
            if (!grown) {
                error = ENOMEM;
                break;
            }
            entries = grown;
            capacity = next_capacity;
        }
        errno = 0;
        struct dirent64 *entry = real_readdir64(dir);
        if (!entry) {
            error = errno;
            break;
        }
        size_t bytes = entry->d_reclen < sizeof(*entry) ? entry->d_reclen : sizeof(*entry);
        memset(&entries[count], 0, sizeof(*entries));
        memcpy(&entries[count++], entry, bytes);
    }
    real_rewinddir(dir);

    struct stat after;
    if (error || fstat(fd, &after) != 0 ||
        before.st_dev != after.st_dev || before.st_ino != after.st_ino ||
        before.st_mtim.tv_sec != after.st_mtim.tv_sec ||
        before.st_mtim.tv_nsec != after.st_mtim.tv_nsec ||
        before.st_ctim.tv_sec != after.st_ctim.tv_sec ||
        before.st_ctim.tv_nsec != after.st_ctim.tv_nsec) {
        free(entries);
        return NULL;
    }

    struct dirent64 *small = realloc(entries, (count ? count : 1) * sizeof(*entries));
    if (small) entries = small;
    free(slot->entries);
    *slot = (struct listing){
        .dev = before.st_dev,
        .ino = before.st_ino,
        .mtime = before.st_mtim,
        .ctime = before.st_ctim,
        .expires = monotonic_ns() + CACHE_TTL_NS,
        .count = count,
        .entries = entries,
    };
    return slot;
}

struct dirent64 *readdir64(DIR *dir) {
    pthread_once(&hooks_once, init_hooks);
    if (!real_readdir64) { errno = ENOSYS; return NULL; }
    if (!is_steam_runtime()) return real_readdir64(dir);

    int saved_errno = errno;
    pthread_mutex_lock(&cache_lock);
    struct reader *reader = find_reader(dir);
    if (!reader) {
        struct listing *listing = listing_for(dir);
        if (listing && (reader = malloc(sizeof(*reader)))) {
            *reader = (struct reader){dir, listing, 0, readers};
            readers = reader;
            listing->readers++;
        }
    }
    struct dirent64 *entry = NULL;
    if (reader && reader->pos < reader->listing->count)
        entry = &reader->listing->entries[reader->pos++];
    pthread_mutex_unlock(&cache_lock);

    if (reader) {
        errno = saved_errno;
        return entry;
    }
    return real_readdir64(dir);
}

/* 32- and 64-bit dirent layouts are identical in the supported 64-bit runtimes. */
struct dirent *readdir(DIR *dir) {
    return (struct dirent *)readdir64(dir);
}

int closedir(DIR *dir) {
    pthread_once(&hooks_once, init_hooks);
    if (!real_closedir) { errno = ENOSYS; return -1; }
    int saved_errno = errno;
    pthread_mutex_lock(&cache_lock);
    release_reader(dir);
    pthread_mutex_unlock(&cache_lock);
    errno = saved_errno;
    return real_closedir(dir);
}

void rewinddir(DIR *dir) {
    pthread_once(&hooks_once, init_hooks);
    int saved_errno = errno;
    pthread_mutex_lock(&cache_lock);
    release_reader(dir);
    pthread_mutex_unlock(&cache_lock);
    if (real_rewinddir) real_rewinddir(dir);
    errno = saved_errno;
}

long telldir(DIR *dir) {
    pthread_once(&hooks_once, init_hooks);
    int saved_errno = errno;
    pthread_mutex_lock(&cache_lock);
    struct reader *reader = find_reader(dir);
    long offset = reader ? (reader->pos ? reader->listing->entries[reader->pos - 1].d_off : 0) : -1;
    pthread_mutex_unlock(&cache_lock);
    if (!reader && real_telldir) return real_telldir(dir);
    if (!reader) { errno = ENOSYS; return -1; }
    errno = saved_errno;
    return offset;
}

void seekdir(DIR *dir, long offset) {
    pthread_once(&hooks_once, init_hooks);
    int saved_errno = errno;
    pthread_mutex_lock(&cache_lock);
    struct reader *reader = find_reader(dir);
    if (reader) {
        size_t pos = 0;
        if (offset) {
            for (; pos < reader->listing->count; pos++)
                if (reader->listing->entries[pos].d_off == offset) { pos++; break; }
            if (!pos || reader->listing->entries[pos - 1].d_off != offset)
                release_reader(dir), reader = NULL;
        }
        if (reader) reader->pos = pos;
    }
    pthread_mutex_unlock(&cache_lock);
    if (real_seekdir) real_seekdir(dir, offset);
    errno = saved_errno;
}
