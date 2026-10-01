#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>

#ifndef MADV_PAGEOUT
#define MADV_PAGEOUT 21
#endif

#ifndef ZR_CTL
#define ZR_CTL "/tmp/.bl-zram"
#endif
#ifndef ZR_LOG
#define ZR_LOG "/tmp/.bl-zram.log"
#endif
#ifndef ZR_LIBC
#define ZR_LIBC "libc.so.6"
#endif
#define ZR_TOUCH 256
#define ZR_RESTORE 0
#define ZR_PAGEOUT 1

static long (*zr_syscall)(long, ...);
static unsigned long zr_page;
static int zr_busy;
static int zr_pending;

struct zr_stats {
  unsigned long regions;
  unsigned long bytes;
};

static int zr_open(const char *path, int flags) {
  return (int)zr_syscall(SYS_openat, AT_FDCWD, path, flags | O_CLOEXEC, 0644);
}

static int zr_mode(void) {
  char c = 0;
  int fd = zr_open(ZR_CTL, O_RDONLY);
  if (fd < 0) return -1;
  ssize_t n = read(fd, &c, 1);
  close(fd);
  if (n != 1 || (c != '0' && c != '1')) return -1;
  return c - '0';
}

static unsigned long zr_hex(const char **p) {
  unsigned long v = 0;
  for (;; (*p)++) {
    char c = **p;
    if (c >= '0' && c <= '9') v = v * 16 + (unsigned long)(c - '0');
    else if (c >= 'a' && c <= 'f') v = v * 16 + (unsigned long)(c - 'a' + 10);
    else return v;
  }
}

static int zr_eligible(const char *line, unsigned long *start, unsigned long *end) {
  const char *p = line;
  *start = zr_hex(&p);
  if (*p++ != '-') return 0;
  *end = zr_hex(&p);
  if (*p++ != ' ') return 0;
  if (p[0] != 'r' || p[1] != 'w' || p[3] != 'p') return 0;
  for (int field = 0; field < 4; field++) {
    while (*p && *p != ' ') p++;
    while (*p == ' ') p++;
  }
  if (*p == 0) return 1;
  return strncmp(p, "[heap]", 6) == 0 || strncmp(p, "[anon:", 6) == 0;
}

static void zr_touch(unsigned long start, unsigned long end) {
  struct iovec remote[ZR_TOUCH];
  char sink[ZR_TOUCH];
  struct iovec local = {sink, 0};
  pid_t self = getpid();
  unsigned long addr = start;
  while (addr < end) {
    int n = 0;
    for (; n < ZR_TOUCH && addr < end; n++, addr += zr_page) {
      remote[n].iov_base = (void *)addr;
      remote[n].iov_len = 1;
    }
    local.iov_len = (size_t)n;
    process_vm_readv(self, &local, 1, remote, (unsigned long)n, 0);
  }
}

static void zr_region(int pass, unsigned long start, unsigned long end, struct zr_stats *st) {
  if (end <= start) return;
  if (pass == ZR_PAGEOUT) {
    if (madvise((void *)start, end - start, MADV_PAGEOUT) != 0) return;
  } else {
    zr_touch(start, end);
  }
  st->regions++;
  st->bytes += end - start;
}

static void zr_walk(int pass, struct zr_stats *st) {
  char buf[8192];
  size_t have = 0;
  int fd = zr_open("/proc/self/maps", O_RDONLY);
  if (fd < 0) return;
  for (;;) {
    ssize_t n = read(fd, buf + have, sizeof(buf) - 1 - have);
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) break;
    have += (size_t)n;
    buf[have] = 0;
    char *line = buf;
    char *nl;
    while ((nl = memchr(line, '\n', have - (size_t)(line - buf))) != NULL) {
      *nl = 0;
      unsigned long s, e;
      if (zr_eligible(line, &s, &e)) zr_region(pass, s, e, st);
      line = nl + 1;
    }
    have -= (size_t)(line - buf);
    memmove(buf, line, have);
    if (have == sizeof(buf) - 1) have = 0;
  }
  close(fd);
}

static char *zr_num(char *p, unsigned long v) {
  char tmp[24];
  int n = 0;
  do {
    tmp[n++] = (char)('0' + v % 10);
    v /= 10;
  } while (v);
  while (n) *p++ = tmp[--n];
  return p;
}

static char *zr_str(char *p, const char *s) {
  while (*s) *p++ = *s++;
  return p;
}

static unsigned long zr_us(clockid_t clock) {
  struct timespec t;
  clock_gettime(clock, &t);
  return (unsigned long)t.tv_sec * 1000000UL + (unsigned long)t.tv_nsec / 1000UL;
}

static void zr_run(int compress) {
  struct zr_stats st = {0, 0};
  unsigned long wall = zr_us(CLOCK_MONOTONIC);
  unsigned long cpu = zr_us(CLOCK_THREAD_CPUTIME_ID);
  if (compress) {
    zr_walk(ZR_PAGEOUT, &st);
  } else {
    zr_walk(ZR_RESTORE, &st);
  }
  wall = zr_us(CLOCK_MONOTONIC) - wall;
  cpu = zr_us(CLOCK_THREAD_CPUTIME_ID) - cpu;
  char line[256];
  char *p = line;
  p = zr_num(p, (unsigned long)getpid());
  p = zr_str(p, " ");
  p = zr_str(p, program_invocation_short_name);
  p = zr_str(p, compress ? " compress regions=" : " restore regions=");
  p = zr_num(p, st.regions);
  p = zr_str(p, " span_kb=");
  p = zr_num(p, st.bytes / 1024);
  p = zr_str(p, " wall_us=");
  p = zr_num(p, wall);
  p = zr_str(p, " cpu_us=");
  p = zr_num(p, cpu);
  *p++ = '\n';
  int fd = zr_open(ZR_LOG, O_WRONLY | O_APPEND | O_CREAT);
  if (fd < 0) return;
  ssize_t ignored = write(fd, line, (size_t)(p - line));
  (void)ignored;
  close(fd);
}

static void zr_handler(int sig, siginfo_t *info, void *context) {
  (void)sig;
  (void)context;
  if (info == NULL || info->si_code != SI_USER) return;
  int saved = errno;
  __atomic_store_n(&zr_pending, 1, __ATOMIC_SEQ_CST);
  while (__atomic_load_n(&zr_pending, __ATOMIC_SEQ_CST)
         && !__atomic_exchange_n(&zr_busy, 1, __ATOMIC_SEQ_CST)) {
    __atomic_store_n(&zr_pending, 0, __ATOMIC_SEQ_CST);
    int mode = zr_mode();
    if (mode >= 0) zr_run(mode);
    __atomic_store_n(&zr_busy, 0, __ATOMIC_SEQ_CST);
  }
  errno = saved;
}

static void zr_child(void) {
  __atomic_store_n(&zr_busy, 0, __ATOMIC_SEQ_CST);
  __atomic_store_n(&zr_pending, 0, __ATOMIC_SEQ_CST);
}

static int zr_target(void) {
  const char *name = program_invocation_short_name;
  return strcmp(name, "steam") == 0 || strcmp(name, "steamwebhelper") == 0;
}

__attribute__((constructor)) static void zr_init(void) {
  if (!zr_target()) return;
  void *libc = dlopen(ZR_LIBC, RTLD_LAZY | RTLD_NOLOAD);
  if (libc == NULL) return;
  zr_syscall = (long (*)(long, ...))dlsym(libc, "syscall");
  if (zr_syscall == NULL) return;
  int fd = zr_open(ZR_CTL, O_RDONLY);
  if (fd < 0) return;
  close(fd);
  long page = sysconf(_SC_PAGESIZE);
  zr_page = page > 0 ? (unsigned long)page : 4096UL;
  struct sigaction old;
  if (sigaction(SIGURG, NULL, &old) != 0) return;
  if ((old.sa_flags & SA_SIGINFO) || old.sa_handler != SIG_DFL) return;
  struct sigaction sa;
  memset(&sa, 0, sizeof(sa));
  sa.sa_sigaction = zr_handler;
  sa.sa_flags = SA_SIGINFO | SA_RESTART;
  sigemptyset(&sa.sa_mask);
  if (sigaction(SIGURG, &sa, NULL) != 0) return;
  pthread_atfork(NULL, NULL, zr_child);
}
