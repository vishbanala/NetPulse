#include "url_guard.h"

#include <ctype.h>
#include <stdio.h>
#include <string.h>

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <winsock2.h>
#include <ws2tcpip.h>
#else
#include <arpa/inet.h>
#include <netdb.h>
#include <sys/socket.h>
#include <sys/types.h>
#endif

/* Explicit public-URL allowlist — keep in sync with ApprovedMonitorUrls.java.
 * Private/metadata/credential SSRF checks below still apply to every entry. */
static const char *const ALLOWED_URLS[] = {
    "https://example.com",
    "https://example.com/",
    "https://www.google.com",
    "https://www.google.com/",
    "https://google.com",
    "https://google.com/",
    "https://httpbin.org/get",
    "https://httpbin.org/status/200",
    "https://httpbin.org/status/500",
    "https://httpbin.org/delay/5",
    NULL
};

static void set_err(char *errbuf, size_t errbuf_len, const char *msg)
{
    if (errbuf == NULL || errbuf_len == 0) {
        return;
    }
    snprintf(errbuf, errbuf_len, "%s", msg);
}

static int str_ieq(const char *a, const char *b)
{
    while (*a && *b) {
        if (tolower((unsigned char)*a) != tolower((unsigned char)*b)) {
            return 0;
        }
        a++;
        b++;
    }
    return *a == '\0' && *b == '\0';
}

static int is_on_allowlist(const char *url)
{
    size_t i;
    for (i = 0; ALLOWED_URLS[i] != NULL; i++) {
        if (str_ieq(url, ALLOWED_URLS[i])) {
            return 1;
        }
    }
    return 0;
}

/* Parse scheme://[userinfo@]host[:port][/path...] */
static int parse_url_parts(const char *url,
                           char *scheme, size_t scheme_len,
                           char *host, size_t host_len,
                           int *has_userinfo)
{
    const char *p;
    const char *host_start;
    const char *host_end;
    size_t n;

    *has_userinfo = 0;

    p = strstr(url, "://");
    if (p == NULL) {
        return -1;
    }

    n = (size_t)(p - url);
    if (n == 0 || n + 1 > scheme_len) {
        return -1;
    }
    memcpy(scheme, url, n);
    scheme[n] = '\0';

    host_start = p + 3;
    if (*host_start == '\0') {
        return -1;
    }

    /* Reject embedded credentials: anything with '@' before the first '/' */
    {
        const char *slash = strchr(host_start, '/');
        const char *at = strchr(host_start, '@');
        if (at != NULL && (slash == NULL || at < slash)) {
            *has_userinfo = 1;
            host_start = at + 1;
        }
    }

    host_end = host_start;
    while (*host_end && *host_end != '/' && *host_end != ':' &&
           *host_end != '?' && *host_end != '#') {
        host_end++;
    }

    n = (size_t)(host_end - host_start);
    if (n == 0 || n + 1 > host_len) {
        return -1;
    }

    /* Strip IPv6 brackets if present */
    if (host_start[0] == '[' && host_start[n - 1] == ']') {
        if (n < 3) {
            return -1;
        }
        memcpy(host, host_start + 1, n - 2);
        host[n - 2] = '\0';
    } else {
        memcpy(host, host_start, n);
        host[n] = '\0';
    }

    return 0;
}

static int ipv4_octet(const char *s, unsigned int *out, const char **end)
{
    unsigned int v = 0;
    int digits = 0;
    while (*s && isdigit((unsigned char)*s)) {
        v = v * 10u + (unsigned int)(*s - '0');
        if (v > 255u) {
            return -1;
        }
        s++;
        digits++;
        if (digits > 3) {
            return -1;
        }
    }
    if (digits == 0) {
        return -1;
    }
    *out = v;
    *end = s;
    return 0;
}

static int parse_ipv4(const char *s, unsigned int out[4])
{
    const char *p = s;
    int i;
    for (i = 0; i < 4; i++) {
        if (ipv4_octet(p, &out[i], &p) != 0) {
            return -1;
        }
        if (i < 3) {
            if (*p != '.') {
                return -1;
            }
            p++;
        }
    }
    return *p == '\0' ? 0 : -1;
}

static int ipv4_is_blocked(unsigned int o[4])
{
    /* 0.0.0.0/8 */
    if (o[0] == 0) {
        return 1;
    }
    /* 10.0.0.0/8 */
    if (o[0] == 10) {
        return 1;
    }
    /* 127.0.0.0/8 loopback */
    if (o[0] == 127) {
        return 1;
    }
    /* 169.254.0.0/16 link-local + cloud metadata */
    if (o[0] == 169 && o[1] == 254) {
        return 1;
    }
    /* 172.16.0.0/12 */
    if (o[0] == 172 && o[1] >= 16 && o[1] <= 31) {
        return 1;
    }
    /* 192.168.0.0/16 */
    if (o[0] == 192 && o[1] == 168) {
        return 1;
    }
    /* 100.64.0.0/10 CGNAT */
    if (o[0] == 100 && o[1] >= 64 && o[1] <= 127) {
        return 1;
    }
    return 0;
}

static int host_name_blocked(const char *host)
{
    if (str_ieq(host, "localhost") ||
        str_ieq(host, "metadata.google.internal") ||
        str_ieq(host, "metadata") ||
        str_ieq(host, "metadata.aws.internal")) {
        return 1;
    }
    /* Block obvious local suffixes */
    {
        size_t len = strlen(host);
        if (len >= 6 && str_ieq(host + len - 6, ".local")) {
            return 1;
        }
        if (len >= 10 && str_ieq(host + len - 10, ".localhost")) {
            return 1;
        }
        if (len >= 5 && str_ieq(host + len - 5, ".internal")) {
            return 1;
        }
    }
    return 0;
}

static int resolved_addresses_blocked(const char *host)
{
#ifdef _WIN32
    WSADATA wsa;
    int wsa_started = 0;
#endif
    struct addrinfo hints;
    struct addrinfo *res = NULL;
    struct addrinfo *ai;
    int blocked = 0;
    int rc;

#ifdef _WIN32
    if (WSAStartup(MAKEWORD(2, 2), &wsa) == 0) {
        wsa_started = 1;
    }
#endif

    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;

    rc = getaddrinfo(host, NULL, &hints, &res);
    if (rc != 0) {
        /* Fail closed: if we cannot resolve, reject. */
        blocked = 1;
        goto done;
    }

    for (ai = res; ai != NULL; ai = ai->ai_next) {
        if (ai->ai_family == AF_INET) {
            struct sockaddr_in *sa = (struct sockaddr_in *)ai->ai_addr;
            unsigned char *b = (unsigned char *)&sa->sin_addr.s_addr;
            unsigned int o[4] = { b[0], b[1], b[2], b[3] };
            if (ipv4_is_blocked(o)) {
                blocked = 1;
                break;
            }
        } else if (ai->ai_family == AF_INET6) {
            struct sockaddr_in6 *sa6 = (struct sockaddr_in6 *)ai->ai_addr;
            const unsigned char *b = sa6->sin6_addr.s6_addr;
            /* ::1 loopback */
            int is_loopback = 1;
            int i;
            for (i = 0; i < 15; i++) {
                if (b[i] != 0) {
                    is_loopback = 0;
                    break;
                }
            }
            if (is_loopback && b[15] == 1) {
                blocked = 1;
                break;
            }
            /* fc00::/7 ULA, fe80::/10 link-local */
            if ((b[0] & 0xfe) == 0xfc || (b[0] == 0xfe && (b[1] & 0xc0) == 0x80)) {
                blocked = 1;
                break;
            }
            /* IPv4-mapped IPv6 */
            if (b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 0 &&
                b[4] == 0 && b[5] == 0 && b[6] == 0 && b[7] == 0 &&
                b[8] == 0 && b[9] == 0 && b[10] == 0xff && b[11] == 0xff) {
                unsigned int o[4] = { b[12], b[13], b[14], b[15] };
                if (ipv4_is_blocked(o)) {
                    blocked = 1;
                    break;
                }
            }
        }
    }

done:
    if (res != NULL) {
        freeaddrinfo(res);
    }
#ifdef _WIN32
    if (wsa_started) {
        WSACleanup();
    }
#endif
    return blocked;
}

int np_url_is_allowed(const char *url, char *errbuf, size_t errbuf_len)
{
    char scheme[16];
    char host[256];
    int has_userinfo = 0;
    unsigned int ipv4[4];

    if (url == NULL || url[0] == '\0') {
        set_err(errbuf, errbuf_len, "URL is empty");
        return -1;
    }

    if (parse_url_parts(url, scheme, sizeof(scheme), host, sizeof(host),
                        &has_userinfo) != 0) {
        set_err(errbuf, errbuf_len, "URL could not be parsed");
        return -1;
    }

    if (!(str_ieq(scheme, "http") || str_ieq(scheme, "https"))) {
        set_err(errbuf, errbuf_len, "Only http and https schemes are allowed");
        return -1;
    }

    if (has_userinfo) {
        set_err(errbuf, errbuf_len, "URLs with embedded credentials are rejected");
        return -1;
    }

    if (!is_on_allowlist(url)) {
        set_err(errbuf, errbuf_len,
                "URL is not on the approved allowlist (Step 3 restriction)");
        return -1;
    }

    if (host_name_blocked(host)) {
        set_err(errbuf, errbuf_len, "Hostname is blocked (localhost/internal)");
        return -1;
    }

    if (parse_ipv4(host, ipv4) == 0 && ipv4_is_blocked(ipv4)) {
        set_err(errbuf, errbuf_len, "Literal private/link-local IP is blocked");
        return -1;
    }

    if (str_ieq(host, "::1") || str_ieq(host, "0:0:0:0:0:0:0:1")) {
        set_err(errbuf, errbuf_len, "IPv6 loopback is blocked");
        return -1;
    }

    if (resolved_addresses_blocked(host)) {
        set_err(errbuf, errbuf_len,
                "Resolved address is private/internal or DNS lookup failed");
        return -1;
    }

    return 0;
}
