#include <dlfcn.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <unistd.h>

typedef void *(*create_interface_fn)(const char *, int *);
typedef int32_t (*create_pipe_fn)(void);
typedef int32_t (*connect_user_fn)(int32_t);
typedef bool (*status_fn)(int32_t, int32_t);
typedef bool (*release_pipe_fn)(int32_t);

static void *lookup(void *handle, const char *name) {
    dlerror();
    void *ptr = dlsym(handle, name);
    const char *error = dlerror();
    printf("dlsym %-28s = %p", name, ptr);
    if (error != NULL) printf(" error=%s", error);
    putchar('\n');
    return ptr;
}

int main(int argc, char **argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: %s LIBSTEAMCLIENT.SO\n", argv[0]);
        return 2;
    }

    printf("pid=%d uid=%d\n", getpid(), getuid());
    void *client = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (client == NULL) {
        fprintf(stderr, "dlopen failed: %s\n", dlerror());
        return 3;
    }

    create_interface_fn create_interface =
            (create_interface_fn)lookup(client, "CreateInterface");
    create_pipe_fn create_pipe =
            (create_pipe_fn)lookup(client, "Steam_CreateSteamPipe");
    connect_user_fn connect_user =
            (connect_user_fn)lookup(client, "Steam_ConnectToGlobalUser");
    status_fn connected =
            (status_fn)lookup(client, "Steam_BConnected");
    status_fn logged_on =
            (status_fn)lookup(client, "Steam_BLoggedOn");
    release_pipe_fn release_pipe =
            (release_pipe_fn)lookup(client, "Steam_BReleaseSteamPipe");

    int rc = -1;
    void *iface = create_interface != NULL
            ? create_interface("SteamClient023", &rc) : NULL;
    printf("SteamClient023=%p rc=%d\n", iface, rc);

    int32_t pipe = create_pipe != NULL ? create_pipe() : 0;
    printf("Steam_CreateSteamPipe=%d\n", pipe);
    int32_t user = (pipe != 0 && connect_user != NULL)
            ? connect_user(pipe) : 0;
    printf("Steam_ConnectToGlobalUser=%d\n", user);

    if (pipe != 0 && user != 0 && connected != NULL) {
        printf("Steam_BConnected=%d\n", connected(user, pipe));
    }
    if (pipe != 0 && user != 0 && logged_on != NULL) {
        printf("Steam_BLoggedOn=%d\n", logged_on(user, pipe));
    }

    if (pipe != 0 && release_pipe != NULL) {
        printf("Steam_BReleaseSteamPipe=%d\n", release_pipe(pipe));
    }
    dlclose(client);

    return pipe != 0 && user != 0 ? 0 : 4;
}
