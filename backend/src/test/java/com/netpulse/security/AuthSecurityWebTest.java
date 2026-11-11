package com.netpulse.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.netpulse.config.ClockConfig;
import com.netpulse.config.SecurityConfig;
import com.netpulse.controller.AuthController;
import com.netpulse.controller.HealthController;
import com.netpulse.controller.MonitorController;
import com.netpulse.controller.RootHealthController;
import com.netpulse.exception.ApiExceptionHandler;
import com.netpulse.mail.MailSender;
import com.netpulse.model.Monitor;
import com.netpulse.model.UserAccount;
import com.netpulse.repository.AuthTokenRepository;
import com.netpulse.repository.IncidentRepository;
import com.netpulse.repository.MonitoringCheckRepository;
import com.netpulse.repository.UserRepository;
import com.netpulse.service.AuthService;
import com.netpulse.service.MonitorService;
import com.netpulse.sqs.MonitoringJobPublisher;

@WebMvcTest(controllers = {
        AuthController.class,
        MonitorController.class,
        HealthController.class,
        RootHealthController.class
})
@Import({
        SecurityConfig.class,
        ApiExceptionHandler.class,
        AuthRateLimiter.class,
        AuthService.class,
        ClockConfig.class,
        PublicUrlValidator.class
})
@TestPropertySource(properties = {
        "netpulse.security.mode=AUTHENTICATED",
        "netpulse.admin.email=admin@example.com",
        "netpulse.admin.username=admin@example.com",
        "netpulse.admin.password-hash=$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG",
        "netpulse.cors.allowed-origins=http://localhost:5173",
        "netpulse.session.cookie-same-site=Lax",
        "netpulse.session.cookie-secure=false",
        "netpulse.auth.login-max-attempts=20",
        "netpulse.auth.login-window-seconds=60"
})
class AuthSecurityWebTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MONITOR_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String ADMIN_HASH = "$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserRepository userRepository;
    @MockitoBean
    private AuthTokenRepository authTokenRepository;
    @MockitoBean
    private MailSender mailSender;
    @MockitoBean
    private MonitorService monitorService;
    @MockitoBean
    private MonitoringCheckRepository monitoringCheckRepository;
    @MockitoBean
    private IncidentRepository incidentRepository;
    @MockitoBean
    private MonitoringJobPublisher monitoringJobPublisher;

    @BeforeEach
    void stubAdminUser() {
        org.assertj.core.api.Assertions.assertThat(
                new BCryptPasswordEncoder().matches("password", ADMIN_HASH)).isTrue();
        when(mailSender.isConfigured()).thenReturn(false);
        when(userRepository.findByEmailNormalized("admin@example.com")).thenReturn(Optional.of(adminAccount()));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(adminAccount()));
    }

    @Test
    void healthEndpointsRemainPublic() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void monitorsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/monitors"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginThenAuthenticatedReadWorks() throws Exception {
        MockHttpSession session = loginAsAdmin();
        when(monitorService.listForUser(USER_ID)).thenReturn(List.of(sampleMonitor()));

        mockMvc.perform(get("/api/monitors").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Example"));
    }

    @Test
    void authenticatedMutatingRequestWithoutCsrfIsForbidden() throws Exception {
        MockHttpSession session = loginAsAdmin();

        mockMvc.perform(put("/api/monitors/{id}", MONITOR_ID)
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Example","url":"https://example.com","intervalSeconds":300,"enabled":true}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void authenticatedMutatingRequestWithCsrfSucceeds() throws Exception {
        MockHttpSession session = loginAsAdmin();
        when(monitorService.update(eq(USER_ID), eq(MONITOR_ID), any(), any(), anyInt(), anyBoolean()))
                .thenReturn(sampleMonitor());

        mockMvc.perform(put("/api/monitors/{id}", MONITOR_ID)
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Example","url":"https://example.com","intervalSeconds":300,"enabled":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Example"));
    }

    @Test
    void logoutClearsSession() throws Exception {
        MockHttpSession session = loginAsAdmin();

        mockMvc.perform(post("/api/auth/logout").with(csrf()).session(session))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/monitors").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginRejectsBadPassword() throws Exception {
        mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"admin@example.com","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void meDoesNotExposePasswordHash() throws Exception {
        MockHttpSession session = loginAsAdmin();
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("admin@example.com"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void registerCreatesUsableAccountWithoutExposingHash() throws Exception {
        when(userRepository.existsByEmailNormalized("new@example.com")).thenReturn(false);
        UserAccount created = new UserAccount();
        created.setId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        created.setEmail("new@example.com");
        created.setPasswordHash(ADMIN_HASH);
        created.setRole(UserAccount.ROLE_USER);
        when(userRepository.insert(eq("new@example.com"), any(), eq(UserAccount.ROLE_USER), eq(null)))
                .thenReturn(created);

        mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"new@example.com","password":"long-enough-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registered").value(true))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("sign in")))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void featuresReportsPasswordResetDisabledWithoutSmtp() throws Exception {
        mockMvc.perform(get("/api/auth/features"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordResetEnabled").value(false))
                .andExpect(jsonPath("$.registrationEnabled").value(true));
    }

    @Test
    void forgotPasswordUnavailableWithoutSmtp() throws Exception {
        mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/forgot-password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"admin@example.com"}
                                """))
                .andExpect(status().isServiceUnavailable());
    }

    private MockHttpSession loginAsAdmin() throws Exception {
        mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk());

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"admin@example.com","password":"password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.email").value("admin@example.com"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();

        return (MockHttpSession) login.getRequest().getSession(false);
    }

    private static UserAccount adminAccount() {
        UserAccount account = new UserAccount();
        account.setId(USER_ID);
        account.setEmail("admin@example.com");
        account.setPasswordHash(ADMIN_HASH);
        account.setRole(UserAccount.ROLE_ADMIN);
        account.setEmailVerifiedAt(OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        return account;
    }

    private static Monitor sampleMonitor() {
        Monitor m = new Monitor();
        m.setId(MONITOR_ID);
        m.setUserId(USER_ID);
        m.setName("Example");
        m.setUrl("https://example.com");
        m.setIntervalSeconds(300);
        m.setEnabled(true);
        m.setCreatedAt(OffsetDateTime.of(2026, 11, 10, 12, 0, 0, 0, ZoneOffset.UTC));
        return m;
    }
}
