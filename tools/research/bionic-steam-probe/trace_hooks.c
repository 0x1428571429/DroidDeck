#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <unistd.h>

static void trace_line(const char *kind, int fd,
                       const struct sockaddr *addr, socklen_t len) {
    char buffer[768];
    const char *detail = "";
    char endpoint[512] = {0};

    if (addr != NULL && addr->sa_family == AF_UNIX &&
            len > offsetof(struct sockaddr_un, sun_path)) {
        const struct sockaddr_un *un = (const struct sockaddr_un *)addr;
        size_t path_len = len - offsetof(struct sockaddr_un, sun_path);
        if (path_len > sizeof(un->sun_path)) path_len = sizeof(un->sun_path);
        if (path_len > 0 && un->sun_path[0] == '\0') {
            snprintf(endpoint, sizeof(endpoint), "@%.*s",
                     (int)(path_len - 1), un->sun_path + 1);
        } else {
            snprintf(endpoint, sizeof(endpoint), "%.*s",
                     (int)path_len, un->sun_path);
        }
        detail = endpoint;
    } else if (addr != NULL && addr->sa_family == AF_INET &&
               len >= sizeof(struct sockaddr_in)) {
        const struct sockaddr_in *in = (const struct sockaddr_in *)addr;
        unsigned long ip = ntohl(in->sin_addr.s_addr);
        snprintf(endpoint, sizeof(endpoint), "%lu.%lu.%lu.%lu:%u",
                 (ip >> 24) & 0xff, (ip >> 16) & 0xff,
                 (ip >> 8) & 0xff, ip & 0xff, ntohs(in->sin_port));
        detail = endpoint;
    }
    int n = snprintf(buffer, sizeof(buffer),
                     "[probe-trace] %s fd=%d family=%d endpoint=%s\n",
                     kind, fd, addr != NULL ? addr->sa_family : -1, detail);
    if (n > 0) write(STDERR_FILENO, buffer, (size_t)n);
}

int socket(int domain, int type, int protocol) {
    int fd = (int)syscall(SYS_socket, domain, type, protocol);
    char buffer[160];
    int n = snprintf(buffer, sizeof(buffer),
                     "[probe-trace] socket domain=%d type=%d protocol=%d -> %d errno=%d\n",
                     domain, type, protocol, fd, fd < 0 ? errno : 0);
    if (n > 0) write(STDERR_FILENO, buffer, (size_t)n);
    return fd;
}

int socketpair(int domain, int type, int protocol, int sv[2]) {
    int rc = (int)syscall(SYS_socketpair, domain, type, protocol, sv);
    char buffer[180];
    int n = snprintf(buffer, sizeof(buffer),
                     "[probe-trace] socketpair domain=%d type=%d -> %d [%d,%d] errno=%d\n",
                     domain, type, rc, rc == 0 ? sv[0] : -1,
                     rc == 0 ? sv[1] : -1, rc < 0 ? errno : 0);
    if (n > 0) write(STDERR_FILENO, buffer, (size_t)n);
    return rc;
}
int connect(int fd, const struct sockaddr *addr, socklen_t len) {
    trace_line("connect", fd, addr, len);
    int rc = (int)syscall(SYS_connect, fd, addr, len);
    if (rc < 0) {
        char buffer[120];
        int n = snprintf(buffer, sizeof(buffer),
                         "[probe-trace] connect rc=%d errno=%d\n", rc, errno);
        if (n > 0) write(STDERR_FILENO, buffer, (size_t)n);
    }
    return rc;
}

int bind(int fd, const struct sockaddr *addr, socklen_t len) {
    trace_line("bind", fd, addr, len);
    int rc = (int)syscall(SYS_bind, fd, addr, len);
    if (rc < 0) {
        char buffer[120];
        int n = snprintf(buffer, sizeof(buffer),
                         "[probe-trace] bind rc=%d errno=%d\n", rc, errno);
        if (n > 0) write(STDERR_FILENO, buffer, (size_t)n);
    }
    return rc;
}

int listen(int fd, int backlog) {
    int rc = (int)syscall(SYS_listen, fd, backlog);
    char buffer[120];
    int n = snprintf(buffer, sizeof(buffer),
                     "[probe-trace] listen fd=%d backlog=%d -> %d errno=%d\n",
                     fd, backlog, rc, rc < 0 ? errno : 0);
    if (n > 0) write(STDERR_FILENO, buffer, (size_t)n);
    return rc;
}
