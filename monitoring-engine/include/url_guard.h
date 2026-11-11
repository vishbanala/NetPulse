#ifndef NETPULSE_URL_GUARD_H
#define NETPULSE_URL_GUARD_H

#include <stddef.h>

/*
 * Returns 0 if the URL is allowed for monitoring, non-zero otherwise.
 * On rejection, writes a short reason into errbuf (may be NULL).
 *
 * Policy:
 * - http/https only, default ports 80/443 (or omitted)
 * - no embedded credentials
 * - reject private/link-local/metadata/multicast destinations (IPv4 + IPv6)
 * - DNS resolution must succeed and every address must be public
 *
 * Pair with np_url_pin_public_resolve + CURLOPT_RESOLVE to mitigate DNS rebinding.
 */
int np_url_is_allowed(const char *url, char *errbuf, size_t errbuf_len);

/*
 * Resolve URL host and build a CURLOPT_RESOLVE rule "host:port:ip" using the
 * first public address. Returns 0 on success.
 */
int np_url_pin_public_resolve(const char *url,
                              char *resolve_rule, size_t resolve_rule_len,
                              char *errbuf, size_t errbuf_len);

#endif /* NETPULSE_URL_GUARD_H */
