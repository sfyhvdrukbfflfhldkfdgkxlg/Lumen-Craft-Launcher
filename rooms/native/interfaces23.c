/* SPDX-License-Identifier: AGPL-3.0-only
 * Terracotta's interface discovery needs IP addresses before Bionic provided getifaddrs in API 24.
 * Only loaded in this dedicated process on API 23. Exposes IPv4/IPv6 via Linux RTM_GETADDR.
 */
#include <errno.h>
#include <ifaddrs.h>
#include <linux/rtnetlink.h>
#include <net/if.h>
#include <netinet/in.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <unistd.h>

struct row {
    struct ifaddrs item;
    char name[IFNAMSIZ];
    struct sockaddr_storage addr, mask, peer;
};

void freeifaddrs(struct ifaddrs *list) {
    while (list) {
        struct ifaddrs *next = list->ifa_next;
        free(list);
        list = next;
    }
}

static void address(struct sockaddr_storage *out, int family, const void *data, int scope) {
    out->ss_family = family;
    if (family == AF_INET) memcpy(&((struct sockaddr_in *)out)->sin_addr, data, 4);
    else {
        struct sockaddr_in6 *v6 = (struct sockaddr_in6 *)out;
        memcpy(&v6->sin6_addr, data, 16);
        if (IN6_IS_ADDR_LINKLOCAL(&v6->sin6_addr)) v6->sin6_scope_id = scope;
    }
}

static int append(struct ifaddrs **list, struct nlmsghdr *msg, int ctl) {
    if (NLMSG_PAYLOAD(msg, 0) < sizeof(struct ifaddrmsg)) return 0;
    struct ifaddrmsg *a = NLMSG_DATA(msg);
    int family = a->ifa_family;
    int size = family == AF_INET ? 4 : family == AF_INET6 ? 16 : 0;
    if (!size || a->ifa_prefixlen > size * 8) return 0;
    const void *local = NULL, *remote = NULL, *broadcast = NULL;
    int length = IFA_PAYLOAD(msg);
    for (struct rtattr *r = IFA_RTA(a); RTA_OK(r, length); r = RTA_NEXT(r, length)) {
        if (RTA_PAYLOAD(r) != size) continue;
        if (r->rta_type == IFA_LOCAL) local = RTA_DATA(r);
        if (r->rta_type == IFA_ADDRESS) remote = RTA_DATA(r);
        if (r->rta_type == IFA_BROADCAST) broadcast = RTA_DATA(r);
    }
    if (!local) local = remote;
    if (!local) return 0;
    struct ifreq req = {0};
    req.ifr_ifindex = a->ifa_index;
    if (ioctl(ctl, SIOCGIFNAME, &req) < 0 || ioctl(ctl, SIOCGIFFLAGS, &req) < 0) return 0;
    struct row *row = calloc(1,sizeof(*row));
    if (!row) return -1;
    memcpy(row->name, req.ifr_name, sizeof(row->name)-1);
    row->item.ifa_name = row->name;
    row->item.ifa_flags = (unsigned short)req.ifr_flags;
    address(&row->addr, family, local, a->ifa_index);
    unsigned char mask[16] = {0};
    for (unsigned bit = 0;bit < a->ifa_prefixlen;bit++) mask[bit/8] |= 0x80 >> (bit%8);
    address(&row->mask, family, mask, 0);
    row->item.ifa_addr = (struct sockaddr *)&row->addr;
    row->item.ifa_netmask = (struct sockaddr *)&row->mask;
    const void *other = (req.ifr_flags & IFF_POINTOPOINT) ? remote : broadcast;
    if (other) {
        address(&row->peer,family,other,a->ifa_index);
        row->item.ifa_broadaddr = (struct sockaddr *)&row->peer;
    }
    row->item.ifa_next = *list;
    *list = &row->item;
    return 0;
}

int getifaddrs(struct ifaddrs **list) {
    if (!list) { errno = EINVAL; return -1; }
    *list = NULL;
    int net = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC, NETLINK_ROUTE);
    if (net < 0) return -1;
    int ctl = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    int result = -1;
    struct {
        struct nlmsghdr header;
        struct ifaddrmsg addr;
    } request = {{.nlmsg_len=NLMSG_LENGTH(sizeof(struct ifaddrmsg)),.nlmsg_type=RTM_GETADDR,
        .nlmsg_flags=NLM_F_REQUEST|NLM_F_DUMP,.nlmsg_seq=1},{.ifa_family=AF_UNSPEC}};
    struct sockaddr_nl kernel = {.nl_family=AF_NETLINK};
    struct timeval tv = {.tv_sec=3};
    if (ctl<0 || setsockopt(net,SOL_SOCKET,SO_RCVTIMEO,&tv,sizeof(tv))<0 ||
        sendto(net,&request,request.header.nlmsg_len,0,(struct sockaddr *)&kernel,sizeof(kernel))<0) goto end;
    for (;;) {
        union { struct nlmsghdr align; unsigned char bytes[16384]; } buf;
        ssize_t len = recv(net,buf.bytes,sizeof(buf.bytes),MSG_TRUNC);
        if (len<0 && errno==EINTR) continue;
        if (len<=0 || len>(ssize_t)sizeof(buf.bytes)) { if (len>=0) errno=EMSGSIZE; break; }
        for (struct nlmsghdr *m=(struct nlmsghdr *)buf.bytes;NLMSG_OK(m,len);m=NLMSG_NEXT(m,len)) {
            if (m->nlmsg_seq!=1) continue;
            if (m->nlmsg_type==NLMSG_DONE) { result=0; goto end; }
            if (m->nlmsg_type==NLMSG_ERROR) { errno=EIO; goto end; }
            if (m->nlmsg_type==RTM_NEWADDR && append(list,m,ctl)<0) goto end;
        }
    }
end:;
    int saved=errno;
    close(net);
    if (ctl>=0) close(ctl);
    if (result<0) { freeifaddrs(*list); *list=NULL; }
    errno=saved;
    return result;
}
