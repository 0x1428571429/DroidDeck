#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

typedef void *(*get_ipc_fn)(void);
typedef void *(*start_thread_fn)(const char *);
typedef void (*void_fn)(void);

static void *lookup(void *handle, const char *name) {
    dlerror();
    void *ptr = dlsym(handle, name);
    const char *error = dlerror();
    printf("dlsym %-28s = %p", name, ptr);
    if (error != NULL) {
        printf(" error=%s", error);
    }
    putchar('\n');
    fflush(stdout);
    return ptr;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr,
                "usage: %s STEAMSERVICE.SO [symbols|getipc|start [arg]]\n",
                argv[0]);
        return 2;
    }
    printf("pid=%d uid=%d euid=%d\n", getpid(), getuid(), geteuid());
    printf("dlopen(%s)\n", argv[1]);
    fflush(stdout);

    void *handle = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (handle == NULL) {
        fprintf(stderr, "dlopen failed: %s\n", dlerror());
        return 3;
    }
    printf("dlopen ok handle=%p\n", handle);

    get_ipc_fn get_ipc =
            (get_ipc_fn)lookup(handle, "SteamService_GetIPCServer");
    start_thread_fn start =
            (start_thread_fn)lookup(handle, "SteamService_StartThread");
    void_fn stop = (void_fn)lookup(handle, "SteamService_Stop");
    void_fn shutdown = (void_fn)lookup(handle, "SteamService_Shutdown");
    lookup(handle, "SteamService_RunMainLoop");
    lookup(handle, "CreateInterface");

    const char *mode = argc >= 3 ? argv[2] : "symbols";
    if (!strcmp(mode, "getipc") || !strcmp(mode, "start")) {
        printf("GetIPCServer before start -> %p\n",
               get_ipc != NULL ? get_ipc() : NULL);
        fflush(stdout);
    }
    if (!strcmp(mode, "start")) {
        const char *arg = argc >= 4 ? argv[3] : NULL;
        printf("StartThread arg=%s\n", arg != NULL ? arg : "<NULL>");
        fflush(stdout);

        void *result = start != NULL ? start(arg) : NULL;
        printf("StartThread -> %p; GetIPCServer -> %p\n",
               result, get_ipc != NULL ? get_ipc() : NULL);
        fflush(stdout);

        sleep(2);
        if (stop != NULL) {
            puts("Stop()");
            fflush(stdout);
            stop();
        }
        if (shutdown != NULL) {
            puts("Shutdown()");
            fflush(stdout);
            shutdown();
        }
    }

    dlclose(handle);
    puts("done");
    return 0;
}
