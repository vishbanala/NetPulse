package com.netpulse.security;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

public final class CurrentUser {

    private CurrentUser() {
    }

    public static NetPulseUserPrincipal requirePrincipal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof NetPulseUserPrincipal principal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return principal;
    }

    public static UUID requireUserId(Authentication authentication) {
        return requirePrincipal(authentication).getId();
    }
}
