#ifndef NETPULSE_URL_GUARD_H
#define NETPULSE_URL_GUARD_H

#include <stddef.h>

/*
 * Returns 0 if the URL is allowed for monitoring, non-zero otherwise.
 * On rejection, writes a short reason into errbuf (may be NULL).
 *
 * Current policy (Step 3):
 * - http/https only
 * - no embedded credentials
 * - explicit allowlist of public test URLs
 * - hostname / literal IP private-range rejection
 * - DNS resolution checked when possible (not a complete DNS-rebinding defense)
 */
int np_url_is_allowed(const char *url, char *errbuf, size_t errbuf_len);

#endif /* NETPULSE_URL_GUARD_H */
