package com.netpulse.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * Server-side public URL policy for monitor create/update/enqueue.
 * Blocks credentials, non-http(s), unsafe ports, and non-public resolved addresses.
 * The C worker re-validates and pins connect IPs independently.
 */
@Component
public class PublicUrlValidator {

    public void validateOrThrow(String rawUrl) {
        String reason = validate(rawUrl);
        if (reason != null) {
            throw new IllegalArgumentException(reason);
        }
    }

    /**
     * @return null if allowed; otherwise a safe error message (no internal details)
     */
    public String validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "URL is required";
        }
        String trimmed = rawUrl.trim();
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException ex) {
            return "URL could not be parsed";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return "Only http and https URLs are allowed";
        }
        if (uri.getRawUserInfo() != null && !uri.getRawUserInfo().isEmpty()) {
            return "URLs with embedded credentials are rejected";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "URL host is required";
        }
        String hostLower = host.toLowerCase(Locale.ROOT);
        if (hostLower.equals("localhost")
                || hostLower.endsWith(".localhost")
                || hostLower.endsWith(".local")
                || hostLower.endsWith(".internal")
                || hostLower.equals("metadata.google.internal")
                || hostLower.equals("metadata.google")
                || hostLower.equals("instance-data")) {
            return "Hostname is not allowed";
        }

        int port = uri.getPort();
        if (port != -1) {
            boolean ok = ("http".equals(scheme) && port == 80)
                    || ("https".equals(scheme) && port == 443);
            if (!ok) {
                return "Only default HTTP (80) and HTTPS (443) ports are allowed";
            }
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            return "DNS resolution failed";
        }
        if (addresses == null || addresses.length == 0) {
            return "DNS resolution failed";
        }
        for (InetAddress address : addresses) {
            if (!isGloballyRoutablePublic(address)) {
                return "Resolved address is not a public destination";
            }
        }
        return null;
    }

    static boolean isGloballyRoutablePublic(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xff;
            int b1 = bytes[1] & 0xff;
            // 0.0.0.0/8
            if (b0 == 0) {
                return false;
            }
            // 100.64.0.0/10 CGNAT
            if (b0 == 100 && b1 >= 64 && b1 <= 127) {
                return false;
            }
            // 169.254.0.0/16 link-local / cloud metadata
            if (b0 == 169 && b1 == 254) {
                return false;
            }
            // 198.18.0.0/15 benchmarking
            if (b0 == 198 && (b1 == 18 || b1 == 19)) {
                return false;
            }
            // 240.0.0.0/4 reserved
            if (b0 >= 240) {
                return false;
            }
            return true;
        }
        if (bytes.length == 16) {
            // IPv4-mapped handled by InetAddress helpers above for many cases; extra ULA/link-local:
            int b0 = bytes[0] & 0xff;
            int b1 = bytes[1] & 0xff;
            // fc00::/7 ULA
            if ((b0 & 0xfe) == 0xfc) {
                return false;
            }
            // fe80::/10 link-local
            if (b0 == 0xfe && (b1 & 0xc0) == 0x80) {
                return false;
            }
            // ff00::/8 multicast
            if (b0 == 0xff) {
                return false;
            }
            // :: (unspecified) / ::1 already covered by isAnyLocal/isLoopback
            return true;
        }
        return false;
    }
}
