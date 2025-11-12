#include <unistd.h>
#include <dlfcn.h>

int main(int argc, char *argv[]) {
    void *handle = dlopen("libselinux.so", RTLD_LAZY);
    if (!handle) {
        handle = dlopen("/system/lib64/libselinux.so", RTLD_LAZY);
        if (!handle) {
            handle = dlopen("/system/lib/libselinux.so", RTLD_LAZY);
        }
    }

    if (handle) {
        int (*setcon)(const char *) = dlsym(handle, "setcon");
        if (setcon) {
            setcon("u:r:init:s0");
        }
        dlclose(handle);
    }

    execvp(argv[1], &argv[1]);
    return 0;
}