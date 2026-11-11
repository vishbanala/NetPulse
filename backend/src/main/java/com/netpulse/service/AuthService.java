package com.netpulse.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.netpulse.mail.MailSender;
import com.netpulse.model.UserAccount;
import com.netpulse.repository.AuthTokenRepository;
import com.netpulse.repository.UserRepository;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final AuthTokenRepository authTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final MailSender mailSender;
    private final Clock clock;
    private final String publicBaseUrl;

    public AuthService(UserRepository userRepository,
                       AuthTokenRepository authTokenRepository,
                       PasswordEncoder passwordEncoder,
                       MailSender mailSender,
                       Clock clock,
                       @Value("${netpulse.public-base-url:}") String publicBaseUrl) {
        this.userRepository = userRepository;
        this.authTokenRepository = authTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailSender = mailSender;
        this.clock = clock;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim().replaceAll("/$", "");
    }

    @Transactional
    public UserAccount register(String email, String password) {
        String normalized = UserRepository.normalizeEmail(email);
        if (normalized.isEmpty() || !normalized.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid email is required");
        }
        if (password == null || password.length() < 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 10 characters");
        }
        if (userRepository.existsByEmailNormalized(normalized)) {
            // Generic message — avoid confirming which emails exist.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to register with the provided details");
        }
        // Email is an account identifier only — not treated as proof of ownership.
        return userRepository.insert(
                normalized,
                passwordEncoder.encode(password),
                UserAccount.ROLE_USER,
                null);
    }

    @Transactional
    public void forgotPassword(String email) {
        if (!mailSender.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Password reset is not available");
        }
        // Always succeed from caller's perspective after mail is configured.
        Optional<UserAccount> user = userRepository.findByEmailNormalized(email);
        if (user.isEmpty()) {
            return;
        }
        String raw = randomToken();
        Instant expires = clock.instant().plus(1, ChronoUnit.HOURS);
        authTokenRepository.invalidatePasswordTokensForUser(user.get().getId());
        authTokenRepository.insertPasswordResetToken(user.get().getId(), sha256(raw), expires);
        String link = publicBaseUrl.isEmpty()
                ? ("token=" + raw)
                : (publicBaseUrl + "/reset-password?token=" + raw);
        try {
            mailSender.send(
                    user.get().getEmail(),
                    "Reset your NetPulse password",
                    "Reset your password:\n\n" + link + "\n\nThis link expires in 1 hour.\n");
        } catch (MailSender.MailSendException ex) {
            log.warn("Failed to send password reset email for userId={}", user.get().getId());
        }
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        if (newPassword == null || newPassword.length() < 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 10 characters");
        }
        if (!mailSender.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Password reset is not available");
        }
        Optional<UUID> userId = authTokenRepository.consumePasswordResetToken(
                sha256(rawToken == null ? "" : rawToken.trim()), clock.instant());
        if (userId.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired reset token");
        }
        userRepository.updatePasswordHash(userId.get(), passwordEncoder.encode(newPassword));
        authTokenRepository.invalidatePasswordTokensForUser(userId.get());
    }

    public boolean isMailConfigured() {
        return mailSender.isConfigured();
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
