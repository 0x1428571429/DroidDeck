#include <dlfcn.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

typedef void *(*create_interface_fn)(const char *, int *);
typedef void *(*service_start_fn)(const char *);
typedef void *(*service_get_ipc_fn)(void);
typedef void (*service_void_fn)(void);
typedef int32_t (*create_pipe_fn)(void);
typedef int32_t (*connect_user_fn)(int32_t);
typedef bool (*connected_fn)(int32_t, int32_t);
typedef bool (*release_pipe_fn)(int32_t);

static void *must_sym(void *handle, const char *name) {
    dlerror();
    void *ptr = dlsym(handle, name);
    const char *error = dlerror();
    printf("dlsym %-28s = %p", name, ptr);
    if (error != NULL) printf(" error=%s", error);
    putchar('\n');
    fflush(stdout);
    return ptr;
}

static void dump_unix_sockets(const char *label) {
    FILE *file = fopen("/proc/self/net/unix", "r");
    if (file == NULL) return;
    printf("--- UNIX sockets: %s ---\n", label);
    char line[1024];
    while (fgets(line, sizeof(line), file) != NULL) {
        if (strstr(line, "steam") != NULL ||
                strstr(line, "Steam") != NULL ||
                strstr(line, "@") != NULL) {
            fputs(line, stdout);
        }
    }
    fclose(file);
    fflush(stdout);
}

static void *open_library(const char *path) {
    printf("dlopen(%s)\n", path);
    fflush(stdout);
    void *handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (handle == NULL) {
        fprintf(stderr, "dlopen failed: %s\n", dlerror());
    } else {
        printf("dlopen ok handle=%p\n", handle);
    }
    return handle;
}

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s STEAMSERVICE.SO LIBSTEAMCLIENT.SO [service_arg]\n",
                argv[0]);
        return 2;
    }

    const char *service_arg = argc >= 4 ? argv[3] : ".";
    printf("pid=%d uid=%d service_arg=%s\n",
           getpid(), getuid(), service_arg);

    void *service = open_library(argv[1]);
    if (service == NULL) return 3;
    service_start_fn service_start =
            (service_start_fn)must_sym(service, "SteamService_StartThread");
    service_get_ipc_fn get_ipc =
            (service_get_ipc_fn)must_sym(service, "SteamService_GetIPCServer");
    service_void_fn stop =
            (service_void_fn)must_sym(service, "SteamService_Stop");
    service_void_fn shutdown =
            (service_void_fn)must_sym(service, "SteamService_Shutdown");

    printf("IPC before start=%p\n", get_ipc != NULL ? get_ipc() : NULL);
    void *started = service_start != NULL ? service_start(service_arg) : NULL;
    printf("StartThread=%p IPC after start=%p\n",
           started, get_ipc != NULL ? get_ipc() : NULL);
    fflush(stdout);
    usleep(200000);
    dump_unix_sockets("after service start");

    void *client = open_library(argv[2]);
    if (client == NULL) return 4;

    create_interface_fn create_interface =
            (create_interface_fn)must_sym(client, "CreateInterface");
    create_pipe_fn create_pipe =
            (create_pipe_fn)must_sym(client, "Steam_CreateSteamPipe");
    connect_user_fn connect_user =
            (connect_user_fn)must_sym(client, "Steam_ConnectToGlobalUser");
    connected_fn connected =
            (connected_fn)must_sym(client, "Steam_BConnected");
    connected_fn logged_on =
            (connected_fn)must_sym(client, "Steam_BLoggedOn");
    release_pipe_fn release_pipe =
            (release_pipe_fn)must_sym(client, "Steam_BReleaseSteamPipe");

    int rc = -999;
    void *steam_client = create_interface != NULL
            ? create_interface("SteamClient023", &rc) : NULL;
    printf("CreateInterface SteamClient023 -> %p rc=%d\n",
           steam_client, rc);

    int32_t adapter_pipe = 0;
    if (steam_client != NULL) {
        void **vtable = *(void ***)steam_client;
        int32_t (*adapter_create_pipe)(void *) =
                (int32_t (*)(void *))vtable[0];
        printf("SteamClient023 vtable=%p CreateSteamPipe=%p\n",
               vtable, (void *)adapter_create_pipe);
        adapter_pipe = adapter_create_pipe(steam_client);
        printf("SteamClient023::CreateSteamPipe -> %d\n", adapter_pipe);
    }

    int32_t pipe = create_pipe != NULL ? create_pipe() : 0;
    printf("Steam_CreateSteamPipe -> %d\n", pipe);
    dump_unix_sockets("after client CreateSteamPipe");

    int32_t user = (connect_user != NULL && pipe != 0)
            ? connect_user(pipe) : 0;
    printf("Steam_ConnectToGlobalUser(%d) -> %d\n", pipe, user);

    if (connected != NULL && user != 0 && pipe != 0) {
        printf("Steam_BConnected -> %d\n", connected(user, pipe));
    }
    if (logged_on != NULL && user != 0 && pipe != 0) {
        printf("Steam_BLoggedOn -> %d\n", logged_on(user, pipe));
    }
    fflush(stdout);

    sleep(2);
    if (release_pipe != NULL && pipe != 0) {
        printf("Steam_BReleaseSteamPipe -> %d\n", release_pipe(pipe));
    }
    if (stop != NULL) stop();
    if (shutdown != NULL) shutdown();

    dlclose(client);
    dlclose(service);
    puts("done");
    return 0;
}
