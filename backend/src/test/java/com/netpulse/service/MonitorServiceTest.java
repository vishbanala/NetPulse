package com.netpulse.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.netpulse.model.Monitor;
import com.netpulse.model.UserAccount;
import com.netpulse.repository.MonitorRepository;
import com.netpulse.repository.UserRepository;
import com.netpulse.security.PublicUrlValidator;

@ExtendWith(MockitoExtension.class)
class MonitorServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private MonitorRepository monitorRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PublicUrlValidator publicUrlValidator;

    private MonitorService monitorService;

    @BeforeEach
    void setUp() {
        monitorService = new MonitorService(monitorRepository, userRepository, publicUrlValidator, 40);
    }

    @Test
    void createRejectsIntervalBelowFiveMinutes() {
        assertThatThrownBy(() -> monitorService.create(USER_ID, "A", "https://example.com", 299, true))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(monitorRepository, never()).insert(any(), anyString(), anyString(), anyInt(), anyBoolean());
    }

    @Test
    void createRejectsFourthEnabledMonitor() {
        UserAccount user = new UserAccount();
        user.setId(USER_ID);
        when(userRepository.lockById(USER_ID)).thenReturn(Optional.of(user));
        when(monitorRepository.countEnabledByUserId(USER_ID)).thenReturn(3);

        assertThatThrownBy(() -> monitorService.create(USER_ID, "A", "https://example.com", 300, true))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    org.assertj.core.api.Assertions.assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    org.assertj.core.api.Assertions.assertThat(rse.getReason()).contains("Maximum of 3");
                });

        verify(monitorRepository, never()).insert(any(), anyString(), anyString(), anyInt(), anyBoolean());
    }

    @Test
    void createRejectsWhenGlobalCapReached() {
        UserAccount user = new UserAccount();
        user.setId(USER_ID);
        when(userRepository.lockById(USER_ID)).thenReturn(Optional.of(user));
        when(monitorRepository.countEnabledByUserId(USER_ID)).thenReturn(1);
        when(monitorRepository.countEnabledGlobal()).thenReturn(40);

        assertThatThrownBy(() -> monitorService.create(USER_ID, "A", "https://example.com", 300, true))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    org.assertj.core.api.Assertions.assertThat(rse.getStatusCode())
                            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    org.assertj.core.api.Assertions.assertThat(rse.getReason()).contains("capacity");
                });

        verify(monitorRepository, never()).insert(any(), anyString(), anyString(), anyInt(), anyBoolean());
    }

    @Test
    void createAllowsDisabledMonitorBeyondCap() {
        UserAccount user = new UserAccount();
        user.setId(USER_ID);
        when(userRepository.lockById(USER_ID)).thenReturn(Optional.of(user));
        Monitor created = new Monitor();
        created.setId(UUID.randomUUID());
        when(monitorRepository.insert(eq(USER_ID), eq("A"), eq("https://example.com"), eq(300), eq(false)))
                .thenReturn(created);

        monitorService.create(USER_ID, "A", "https://example.com", 300, false);

        verify(monitorRepository).insert(USER_ID, "A", "https://example.com", 300, false);
        verify(monitorRepository, never()).countEnabledByUserId(any());
    }
}
