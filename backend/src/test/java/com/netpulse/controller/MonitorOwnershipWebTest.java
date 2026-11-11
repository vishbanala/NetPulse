package com.netpulse.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;

import com.netpulse.exception.ApiExceptionHandler;
import com.netpulse.model.Monitor;
import com.netpulse.model.UserAccount;
import com.netpulse.repository.IncidentRepository;
import com.netpulse.repository.MonitoringCheckRepository;
import com.netpulse.repository.UserRepository;
import com.netpulse.security.AuthRateLimiter;
import com.netpulse.security.NetPulseUserPrincipal;
import com.netpulse.security.PublicUrlValidator;
import com.netpulse.service.MonitorService;
import com.netpulse.sqs.MonitoringJobPublisher;

@WebMvcTest(controllers = MonitorController.class)
@Import(ApiExceptionHandler.class)
class MonitorOwnershipWebTest {

    private static final UUID USER_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID USER_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID MONITOR_B = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MonitorService monitorService;
    @MockitoBean
    private UserRepository userRepository;
    @MockitoBean
    private MonitoringCheckRepository monitoringCheckRepository;
    @MockitoBean
    private IncidentRepository incidentRepository;
    @MockitoBean
    private MonitoringJobPublisher monitoringJobPublisher;
    @MockitoBean
    private PublicUrlValidator publicUrlValidator;
    @MockitoBean
    private AuthRateLimiter authRateLimiter;

    @Test
    void userCannotReadAnotherUsersMonitor() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Monitor not found"))
                .when(monitorService).requireOwned(MONITOR_B, USER_A);

        mockMvc.perform(get("/api/monitors/{id}", MONITOR_B).with(auth(USER_A, true)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Monitor not found"));
    }

    @Test
    void userCannotDeleteAnotherUsersMonitor() throws Exception {
        when(authRateLimiter.allow(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Monitor not found"))
                .when(monitorService).delete(USER_A, MONITOR_B);

        mockMvc.perform(delete("/api/monitors/{id}", MONITOR_B)
                        .with(auth(USER_A, true))
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void newlyRegisteredUserCanCreateMonitorWithoutEmailVerification() throws Exception {
        when(authRateLimiter.allow(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        Monitor created = new Monitor();
        created.setId(UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"));
        created.setUserId(USER_A);
        created.setName("Example");
        created.setUrl("https://example.com");
        created.setIntervalSeconds(300);
        created.setEnabled(true);
        created.setCreatedAt(OffsetDateTime.of(2026, 11, 10, 12, 0, 0, 0, ZoneOffset.UTC));
        when(monitorService.create(eq(USER_A), eq("Example"), eq("https://example.com"), eq(300), eq(true)))
                .thenReturn(created);

        mockMvc.perform(post("/api/monitors")
                        .with(auth(USER_A, false))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Example","url":"https://example.com","intervalSeconds":300,"enabled":true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Example"));
    }

    @Test
    void listReturnsOnlyCallerScopedServiceResult() throws Exception {
        Monitor owned = new Monitor();
        owned.setId(UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"));
        owned.setUserId(USER_A);
        owned.setName("Mine");
        owned.setUrl("https://example.com");
        owned.setIntervalSeconds(300);
        owned.setEnabled(true);
        owned.setCreatedAt(OffsetDateTime.of(2026, 11, 10, 12, 0, 0, 0, ZoneOffset.UTC));
        when(monitorService.listForUser(USER_A)).thenReturn(List.of(owned));

        mockMvc.perform(get("/api/monitors").with(auth(USER_A, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Mine"));

        verify(monitorService).listForUser(USER_A);
    }

    private static RequestPostProcessor auth(UUID userId, boolean verified) {
        UserAccount account = new UserAccount();
        account.setId(userId);
        account.setEmail(userId.equals(USER_A) ? "a@example.com" : "b@example.com");
        account.setPasswordHash("$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG");
        account.setRole(UserAccount.ROLE_USER);
        if (verified) {
            account.setEmailVerifiedAt(OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        }
        NetPulseUserPrincipal principal = new NetPulseUserPrincipal(account);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, principal.getPassword(), principal.getAuthorities()));
    }
}
