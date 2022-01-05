#include <stdio.h>
#include <unistd.h>

int main(int ac, char *av[]) {
  int ch;
  while ((ch = getopt(ac, av, "f:n:p:D:")) != -1) {
    continue;
  }
  ac -= optind;
  av += optind;
  return execvp(av[0], av);
}
