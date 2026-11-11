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
import com.netpulse.model.Monitor;
import com.netpulse.repository.IncidentRepository;
import com.netpulse.repository.MonitorRepository;
import com.netpulse.repository.MonitoringCheckRepository;
import com.netpulse.sqs.MonitoringJobPublisher;

@WebMvcTest(controllers = {
        AuthController.class,
        MonitorController.class,
        HealthController.class,
        RootHealthController.class
})
@Import({SecurityConfig.class, ApiExceptionHandler.class, LoginRateLimiter.class, ClockConfig.class})
@TestPropertySource(properties = {
        "netpulse.security.mode=AUTHENTICATED",
        "netpulse.admin.username=admin",
        // BCrypt for plaintext "password" (Spring Security documentation example hash)
        "netpulse.admin.password-hash=$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG",
        "netpulse.cors.allowed-origins=http://localhost:5173",
        "netpulse.session.cookie-same-site=Lax",
        "netpulse.session.cookie-secure=false",
        "netpulse.auth.login-max-attempts=20",
        "netpulse.auth.login-window-seconds=60"
})
class AuthSecurityWebTest {

    private static final UUID MONITOR_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MonitorRepository monitorRepository;
    @MockitoBean
    private MonitoringCheckRepository monitoringCheckRepository;
    @MockitoBean
    private IncidentRepository incidentRepository;
    @MockitoBean
    private MonitoringJobPublisher monitoringJobPublisher;

    @BeforeEach
    void verifyKnownHash() {
        // Guard against accidental hash/password drift in this fixture.
        org.assertj.core.api.Assertions.assertThat(
                new BCryptPasswordEncoder().matches(
                        "password",
                        "$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG"))
                .isTrue();
    }

    @Test
    void healthEndpointsRemainPublic() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.securityMode").doesNotExist());

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

        when(monitorRepository.findAll()).thenReturn(List.of(sampleMonitor()));

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
                                {"name":"Example","url":"https://example.com","intervalSeconds":60,"enabled":true}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void authenticatedMutatingRequestWithCsrfSucceeds() throws Exception {
        MockHttpSession session = loginAsAdmin();
        when(monitorRepository.update(eq(MONITOR_ID), any(), any(), anyInt(), anyBoolean()))
                .thenReturn(Optional.of(sampleMonitor()));

        mockMvc.perform(put("/api/monitors/{id}", MONITOR_ID)
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Example","url":"https://example.com","intervalSeconds":60,"enabled":true}
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
                                {"username":"admin","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
    }

    private MockHttpSession loginAsAdmin() throws Exception {
        mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk());

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();

        return (MockHttpSession) login.getRequest().getSession(false);
    }

    private static Monitor sampleMonitor() {
        Monitor m = new Monitor();
        m.setId(MONITOR_ID);
        m.setName("Example");
        m.setUrl("https://example.com");
        m.setIntervalSeconds(60);
        m.setEnabled(true);
        m.setCreatedAt(OffsetDateTime.of(2026, 11, 10, 12, 0, 0, 0, ZoneOffset.UTC));
        return m;
    }
}
