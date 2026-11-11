package com.netpulse.config;

import java.io.IOException;
import java.util.Optional;

import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.netpulse.model.UserAccount;
import com.netpulse.repository.UserRepository;
import com.netpulse.security.NetPulseUserPrincipal;
import com.netpulse.security.SecurityModes;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Attaches a verified local principal in UNAUTHENTICATED_LOCALHOST_ONLY so ownership-scoped
 * controllers keep working after multi-user migration without requiring a login form.
 * Registered only inside {@link SecurityConfig}'s security filter chain.
 */
public class LocalDevPrincipalFilter extends OncePerRequestFilter {

    static final String LOCAL_DEV_EMAIL = "local@localhost";

    private final String securityMode;
    private final UserRepository userRepository;

    public LocalDevPrincipalFilter(Environment environment, UserRepository userRepository) {
        this.securityMode = SecurityModes.normalize(
                environment.getProperty("netpulse.security.mode", SecurityModes.UNAUTHENTICATED_LOCALHOST_ONLY));
        this.userRepository = userRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !SecurityModes.isLocalUnauthenticated(securityMode);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null
                || !SecurityContextHolder.getContext().getAuthentication().isAuthenticated()
                || !(SecurityContextHolder.getContext().getAuthentication().getPrincipal()
                instanceof NetPulseUserPrincipal)) {
            Optional<UserAccount> local = userRepository.findByEmailNormalized(LOCAL_DEV_EMAIL);
            if (local.isEmpty()) {
                filterChain.doFilter(request, response);
                return;
            }
            NetPulseUserPrincipal principal = new NetPulseUserPrincipal(local.get());
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    principal, principal.getPassword(), principal.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        filterChain.doFilter(request, response);
    }
}
