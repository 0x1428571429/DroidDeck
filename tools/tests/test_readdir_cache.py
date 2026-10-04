import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / "linuxfs/preload/readdir_cache.c"

# Mark test directories as FUSE and count reads below the cache. Blocking one stream verifies
# that its disk I/O does not hold up a different stream; the harness has a deadlock timeout.
SHIM = r"""
#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <pthread.h>
#include <sys/statfs.h>
static int calls, blocked_fd = -1, entered;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t condition = PTHREAD_COND_INITIALIZER;
int fstatfs(int fd, struct statfs *st) {
    int (*real)(int, struct statfs *) = dlsym(RTLD_NEXT, "fstatfs");
    int result = real(fd, st);
    if (!result) st->f_type = 0x65735546;
    return result;
}
int probe_calls(void) { return __atomic_load_n(&calls, __ATOMIC_RELAXED); }
void probe_block(int fd) { blocked_fd = fd; entered = 0; }
void probe_wait(void) {
    pthread_mutex_lock(&lock);
    while (!entered) pthread_cond_wait(&condition, &lock);
    pthread_mutex_unlock(&lock);
}
void probe_release(void) {
    pthread_mutex_lock(&lock);
    blocked_fd = -1;
    pthread_cond_broadcast(&condition);
    pthread_mutex_unlock(&lock);
}
struct dirent64 *readdir64(DIR *dir) {
    struct dirent64 *(*real)(DIR *) = dlsym(RTLD_NEXT, "readdir64");
    __atomic_add_fetch(&calls, 1, __ATOMIC_RELAXED);
    pthread_mutex_lock(&lock);
    if (dirfd(dir) == blocked_fd) {
        entered = 1;
        pthread_cond_broadcast(&condition);
        while (blocked_fd >= 0) pthread_cond_wait(&condition, &lock);
    }
    pthread_mutex_unlock(&lock);
    return real(dir);
}
"""

PROGRAM = r"""
#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#define CHECK(c) do { if (!(c)) { fprintf(stderr, "failed line %d\n", __LINE__); exit(1); } } while (0)
static int count(const char *path, const char *wanted, int *found) {
    DIR *dir = opendir(path);
    CHECK(dir);
    int n = 0;
    struct dirent *entry;
    errno = 73;
    while ((entry = readdir(dir))) {
        n++;
        if (wanted && !strcmp(entry->d_name, wanted)) *found = 1;
    }
    CHECK(errno == 73);
    CHECK(closedir(dir) == 0);
    return n;
}
static void *read_one(void *dir) { CHECK(readdir(dir)); return NULL; }
int main(int argc, char **argv) {
    CHECK(argc == 3);
    alarm(10);
    int (*calls)(void) = dlsym(RTLD_DEFAULT, "probe_calls");
    void (*block)(int) = dlsym(RTLD_DEFAULT, "probe_block");
    void (*wait)(void) = dlsym(RTLD_DEFAULT, "probe_wait");
    void (*release)(void) = dlsym(RTLD_DEFAULT, "probe_release");
    DIR *dir = opendir(argv[1]);
    CHECK(dir && readdir(dir));
    CHECK(calls() == 1); /* An early lookup must not read all 5,000 files. */
    long offset = telldir(dir);
    char expected[256];
    struct dirent *entry = readdir(dir);
    CHECK(entry);
    strcpy(expected, entry->d_name);
    seekdir(dir, offset);
    CHECK((entry = readdir(dir)) && !strcmp(entry->d_name, expected));
    CHECK(closedir(dir) == 0);
    int before = calls();
    CHECK(count(argv[1], NULL, NULL) == 5002 && calls() > before);
    before = calls();
    CHECK(count(argv[1], NULL, NULL) == 5002 && calls() == before);
    dir = opendir(argv[1]);
    CHECK(readdir(dir));
    offset = telldir(dir);
    CHECK((entry = readdir(dir)));
    strcpy(expected, entry->d_name);
    seekdir(dir, offset);
    CHECK((entry = readdir(dir)) && !strcmp(entry->d_name, expected));
    char path[4096];
    snprintf(path, sizeof(path), "%s/created", argv[1]);
    int fd = open(path, O_CREAT | O_WRONLY | O_EXCL, 0600);
    CHECK(fd >= 0 && close(fd) == 0);
    rewinddir(dir);
    int found = 0, n = 0;
    errno = 73;
    while ((entry = readdir(dir))) { n++; if (!strcmp(entry->d_name, "created")) found = 1; }
    CHECK(n == 5003 && found && errno == 73 && closedir(dir) == 0);
    CHECK(unlink(path) == 0);
    found = 0;
    CHECK(count(argv[1], "created", &found) == 5002 && !found);
    dir = opendir(argv[2]);
    CHECK(dir);
    block(dirfd(dir));
    pthread_t worker;
    CHECK(pthread_create(&worker, NULL, read_one, dir) == 0);
    wait();
    CHECK(count(argv[1], NULL, NULL) == 5002); /* Must complete while the other stream is blocked. */
    release();
    CHECK(pthread_join(worker, NULL) == 0 && closedir(dir) == 0);
    puts("lazy reads, complete large listings, seek, mutation and concurrent streams passed");
    return 0;
}
"""


@unittest.skipUnless(sys.platform.startswith("linux") and shutil.which("cc"), "Linux and a C compiler are needed")
class ReaddirCacheTest(unittest.TestCase):
    def test_streaming_cache_preserves_directory_behavior(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            exe = base / "Steam/steamrtarm64/steam"
            exe.parent.mkdir(parents=True)
            large, other = base / "large", base / "other"
            large.mkdir()
            other.mkdir()
            for i in range(5000):
                (large / str(i)).touch()
            for name, source in (("shim", SHIM), ("probe", PROGRAM)):
                (base / f"{name}.c").write_text(source)
            cache, shim = base / "cache.so", base / "shim.so"
            for target, source in ((cache, SOURCE), (shim, base / "shim.c")):
                subprocess.run(["cc", "-O2", "-Wall", "-Wextra", "-Werror", "-fPIC", "-shared", "-pthread",
                                "-o", str(target), str(source), "-ldl"], check=True)
            subprocess.run(["cc", "-O2", "-Wall", "-Wextra", "-Werror", "-pthread", "-o", str(exe),
                            str(base / "probe.c"), "-ldl"], check=True)
            result = subprocess.run([str(exe), str(large), str(other)],
                                    env=dict(os.environ, LD_PRELOAD=f"{cache}:{shim}"),
                                    capture_output=True, text=True, timeout=15)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
