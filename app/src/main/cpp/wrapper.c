#include <unistd.h>
#include <sys/selinux.h>

int main(int argc, char *argv[]) {
    setcon("u:r:init:s0");
    return execvp(argv[1], &argv[1]);
}