/* Standalone regression check for the companion's API 23 IPv4/IPv6 address discovery. */
#include <ifaddrs.h>
#include <net/if.h>
#include <netinet/in.h>
#include <stdio.h>

extern int getifaddrs(struct ifaddrs **);
extern void freeifaddrs(struct ifaddrs *);

int main(void) {
    struct ifaddrs *list = NULL;
    if (getifaddrs(&list) != 0) { perror("getifaddrs"); return 1; }
    int loop = 0, external = 0;
    for (struct ifaddrs *p = list; p; p = p->ifa_next) {
        if (!p->ifa_name || !p->ifa_addr || !p->ifa_netmask ||
            p->ifa_addr->sa_family != p->ifa_netmask->sa_family) return 2;
        if (p->ifa_addr->sa_family == AF_INET && (p->ifa_flags & IFF_UP)) {
            if (p->ifa_flags & IFF_LOOPBACK) loop++;
            else external++;
        }
    }
    freeifaddrs(list);
    printf("IPv4 active interface count: %d loopback, %d other\n", loop, external);
    return loop && external ? 0 : 3;
}
