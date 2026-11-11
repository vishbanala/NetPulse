package com.netpulse.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.netpulse.dto.ForgotPasswordRequest;
import com.netpulse.dto.LoginRequest;
import com.netpulse.dto.RegisterRequest;
import com.netpulse.dto.ResetPasswordRequest;
import com.netpulse.repository.UserRepository;
import com.netpulse.security.AuthRateLimiter;
import com.netpulse.security.CurrentUser;
import com.netpulse.security.NetPulseUserPrincipal;
import com.netpulse.service.AuthService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final AuthRateLimiter authRateLimiter;
    private final AuthService authService;

    public AuthController(AuthenticationManager authenticationManager,
                          AuthRateLimiter authRateLimiter,
                          AuthService authService) {
        this.authenticationManager = authenticationManager;
        this.authRateLimiter = authRateLimiter;
        this.authService = authService;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken csrfToken) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("headerName", csrfToken.getHeaderName());
        body.put("parameterName", csrfToken.getParameterName());
        body.put("token", csrfToken.getToken());
        return body;
    }

    /** Public feature flags for the SPA (no secrets). */
    @GetMapping("/features")
    public Map<String, Object> features() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("passwordResetEnabled", authService.isMailConfigured());
        body.put("registrationEnabled", true);
        return body;
    }

    @PostMapping("/register")
    public Map<String, Object> register(@Valid @RequestBody RegisterRequest request,
                                        HttpServletRequest httpRequest) {
        String key = "register:" + clientKey(httpRequest);
        if (!authRateLimiter.allow(key, 5, 60_000L)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Try again later.");
        }
        authService.register(request.getEmail(), request.getPassword());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("registered", true);
        body.put("message", "Account created. You can sign in and start monitoring right away.");
        return body;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest request,
                                     HttpServletRequest httpRequest) {
        String email = UserRepository.normalizeEmail(request.resolvedEmail());
        if (email.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is required");
        }
        String clientKey = clientKey(httpRequest);
        if (!authRateLimiter.allow("login-ip:" + clientKey)
                || !authRateLimiter.allow("login-email:" + email)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many login attempts. Try again later.");
        }

        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, request.getPassword()));

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);

            HttpSession session = httpRequest.getSession(true);
            httpRequest.changeSessionId();
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

            authRateLimiter.reset("login-ip:" + clientKey);
            authRateLimiter.reset("login-email:" + email);

            return meBody(CurrentUser.requirePrincipal(authentication));
        } catch (BadCredentialsException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        return meBody(CurrentUser.requirePrincipal(authentication));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
                                               HttpServletRequest httpRequest) {
        if (!authService.isMailConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Password reset is not available");
        }
        String key = "forgot:" + clientKey(httpRequest);
        if (!authRateLimiter.allow(key, 5, 60_000L)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Try again later.");
        }
        authService.forgotPassword(request.getEmail());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.getToken(), request.getPassword());
        return ResponseEntity.noContent().build();
    }

    private static Map<String, Object> meBody(NetPulseUserPrincipal principal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authenticated", true);
        body.put("id", principal.getId().toString());
        body.put("email", principal.getEmail());
        body.put("username", principal.getEmail());
        body.put("role", principal.getRole());
        return body;
    }

    private static String clientKey(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }
}
