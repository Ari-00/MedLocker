package com.medlocker.service;

import com.medlocker.repository.ShareSessionRepository;
import com.medlocker.repository.PasswordResetRepository;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PinCleanupScheduler {
    private final ShareSessionRepository shares;
    private final PasswordResetRepository resets;

    public PinCleanupScheduler(ShareSessionRepository shares, PasswordResetRepository resets) {
        this.shares = shares;
        this.resets = resets;
    }

    @Scheduled(fixedDelay = 60_000)
    public void purge() {
        shares.purgeExpired(Instant.now());
        resets.purgeExpired(Instant.now());
    }
}
