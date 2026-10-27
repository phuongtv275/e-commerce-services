package com.example.identityservice.scheduler;

import com.example.identityservice.models.repositories.PasswordResetRepository;
import com.example.identityservice.models.repositories.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class IdentityHousekeepingScheduler {

    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetRepository passwordResetRepository;

    @Scheduled(cron = "${identity.housekeeping.cron:0 0 2 * * ?}")
    @Transactional
    public void runHousekeeping() {
        log.info("Starting identity housekeeping purge job...");

        Instant now = Instant.now();
        Instant refreshTokenCutoff = now.minus(30, ChronoUnit.DAYS);
        Instant passwordResetCutoff = now.minus(7, ChronoUnit.DAYS);

        int deletedRefreshTokens = refreshTokenRepository.deleteExpiredOrRevokedTokens(refreshTokenCutoff);
        int deletedPasswordResets = passwordResetRepository.deleteExpiredOrUsedResets(passwordResetCutoff);

        log.info("Identity housekeeping purge job completed: deleted {} expired/revoked refresh tokens (cutoff: {}), deleted {} expired/used password resets (cutoff: {})",
                deletedRefreshTokens, refreshTokenCutoff, deletedPasswordResets, passwordResetCutoff);
    }
}
