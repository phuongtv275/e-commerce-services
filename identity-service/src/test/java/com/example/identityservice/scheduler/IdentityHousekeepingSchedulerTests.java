package com.example.identityservice.scheduler;

import com.example.identityservice.models.repositories.PasswordResetRepository;
import com.example.identityservice.models.repositories.RefreshTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdentityHousekeepingSchedulerTests {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordResetRepository passwordResetRepository;

    @InjectMocks
    private IdentityHousekeepingScheduler scheduler;

    @Test
    @DisplayName("ISSUE-05: Scheduler executes purge with 30-day cutoff for refresh tokens and 7-day cutoff for password resets")
    void testRunHousekeepingExecutesPurgeWithCorrectRetentionCutoffs() {
        when(refreshTokenRepository.deleteExpiredOrRevokedTokens(any(Instant.class))).thenReturn(15);
        when(passwordResetRepository.deleteExpiredOrUsedResets(any(Instant.class))).thenReturn(8);

        Instant beforeRun = Instant.now();
        scheduler.runHousekeeping();
        Instant afterRun = Instant.now();

        ArgumentCaptor<Instant> tokenCutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(refreshTokenRepository).deleteExpiredOrRevokedTokens(tokenCutoffCaptor.capture());

        ArgumentCaptor<Instant> resetCutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(passwordResetRepository).deleteExpiredOrUsedResets(resetCutoffCaptor.capture());

        Instant expectedTokenCutoff = beforeRun.minus(30, ChronoUnit.DAYS);
        assertThat(tokenCutoffCaptor.getValue()).isCloseTo(expectedTokenCutoff, within(5, ChronoUnit.SECONDS));

        Instant expectedResetCutoff = beforeRun.minus(7, ChronoUnit.DAYS);
        assertThat(resetCutoffCaptor.getValue()).isCloseTo(expectedResetCutoff, within(5, ChronoUnit.SECONDS));
    }
}
