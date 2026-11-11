package com.netpulse.config;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.netpulse.security.AdminAuthProperties;
import com.netpulse.security.CorsProperties;
import com.netpulse.security.SecurityModes;
import com.netpulse.security.SessionCookieProperties;
import com.netpulse.security.SpaCsrfTokenRequestHandler;

import jakarta.annotation.PostConstruct;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({
        AdminAuthProperties.class,
        CorsProperties.class,
        SessionCookieProperties.class
})
public class SecurityConfig {

    private final String securityMode;
    private final AdminAuthProperties adminAuthProperties;
    private final CorsProperties corsProperties;
    private final SessionCookieProperties sessionCookieProperties;

    public SecurityConfig(Environment environment,
                          AdminAuthProperties adminAuthProperties,
                          CorsProperties corsProperties,
                          SessionCookieProperties sessionCookieProperties) {
        this.securityMode = SecurityModes.normalize(
                environment.getProperty("netpulse.security.mode", SecurityModes.UNAUTHENTICATED_LOCALHOST_ONLY));
        this.adminAuthProperties = adminAuthProperties;
        this.corsProperties = corsProperties;
        this.sessionCookieProperties = sessionCookieProperties;
    }

    @PostConstruct
    void validateAuthenticatedModeConfig() {
        if (!SecurityModes.isAuthenticated(securityMode)) {
            return;
        }
        if (adminAuthProperties.getUsername().isEmpty()) {
            throw new IllegalStateException(
                    "NETPULSE_ADMIN_USERNAME is required when NETPULSE_SECURITY_MODE=AUTHENTICATED");
        }
        if (adminAuthProperties.getPasswordHash().isEmpty()
                || !adminAuthProperties.getPasswordHash().startsWith("$2")) {
            throw new IllegalStateException(
                    "NETPULSE_ADMIN_PASSWORD_HASH must be a BCrypt hash when NETPULSE_SECURITY_MODE=AUTHENTICATED");
        }
        // CORS origins are optional for same-origin SPA hosting; required for cross-origin UIs.
        if ("None".equalsIgnoreCase(sessionCookieProperties.getCookieSameSite())
                && !sessionCookieProperties.isCookieSecure()) {
            throw new IllegalStateException(
                    "NETPULSE_SESSION_COOKIE_SECURE=true is required when SameSite=None");
        }
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService() {
        return username -> {
            if (!SecurityModes.isAuthenticated(securityMode)) {
                throw new UsernameNotFoundException("Authentication is disabled in this security mode");
            }
            if (!adminAuthProperties.getUsername().equals(username)) {
                throw new UsernameNotFoundException("User not found");
            }
            return User.withUsername(adminAuthProperties.getUsername())
                    .password(adminAuthProperties.getPasswordHash())
                    .roles("ADMIN")
                    .build();
        };
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(passwordEncoder);
        provider.setUserDetailsService(userDetailsService);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.headers(headers -> headers.frameOptions(frame -> frame.deny()));

        if (SecurityModes.isLocalUnauthenticated(securityMode)) {
            http.csrf(csrf -> csrf.disable());
            http.cors(cors -> cors.disable());
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            http.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
            return http.build();
        }

        if (SecurityModes.isHealthOnly(securityMode)) {
            http.csrf(csrf -> csrf.disable());
            http.cors(cors -> cors.disable());
            http.authorizeHttpRequests(auth -> auth
                    .requestMatchers("/health", "/api/health", "/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .anyRequest().denyAll());
            http.exceptionHandling(ex -> ex
                    .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
            return http.build();
        }

        CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepo.setCookieCustomizer(cookie -> {
            cookie.path("/");
            cookie.sameSite(sessionCookieProperties.getCookieSameSite());
            cookie.secure(sessionCookieProperties.isCookieSecure());
        });

        http.cors(Customizer.withDefaults());
        http.csrf(csrf -> csrf
                .csrfTokenRepository(csrfRepo)
                .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()));
        http.sessionManagement(sm -> sm
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation(sf -> sf.changeSessionId()));
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/health", "/api/health", "/actuator/health", "/actuator/health/**")
                .permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers("/api/auth/logout", "/api/auth/me").authenticated()
                .requestMatchers("/api/monitors", "/api/monitors/**").authenticated()
                // Same-origin SPA assets + client routes (API remains protected above).
                .requestMatchers(HttpMethod.GET,
                        "/",
                        "/index.html",
                        "/assets/**",
                        "/favicon.svg",
                        "/favicon.ico",
                        "/icons.svg")
                .permitAll()
                .requestMatchers(request -> {
                    String path = request.getRequestURI();
                    String context = request.getContextPath() == null ? "" : request.getContextPath();
                    if (!context.isEmpty() && path.startsWith(context)) {
                        path = path.substring(context.length());
                    }
                    return "GET".equalsIgnoreCase(request.getMethod())
                            && !path.startsWith("/api/")
                            && !path.startsWith("/actuator/");
                }).permitAll()
                .anyRequest().denyAll());
        http.exceptionHandling(ex -> ex
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                .accessDeniedHandler((request, response, accessDeniedException) ->
                        response.setStatus(HttpStatus.FORBIDDEN.value())));
        http.logout(logout -> logout.disable());
        http.httpBasic(basic -> basic.disable());
        http.formLogin(form -> form.disable());
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (!SecurityModes.isAuthenticated(securityMode) || corsProperties.allowedOriginList().isEmpty()) {
            return source;
        }
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsProperties.allowedOriginList());
        config.setAllowCredentials(true);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-XSRF-TOKEN", "X-Requested-With"));
        config.setExposedHeaders(List.of("X-NetPulse-Security-Mode"));
        config.setMaxAge(3600L);
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
