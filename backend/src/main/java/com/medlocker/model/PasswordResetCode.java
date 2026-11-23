package com.medlocker.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "password_reset_codes", indexes = @Index(columnList = "expires_at"))
public class PasswordResetCode {
    @Id
    private UUID userId;
    @Column(nullable = false, length = 64)
    private String codeHash;
    @Column(nullable = false, name = "expires_at")
    private Instant expiresAt;
    @Column(nullable = false)
    private int attempts;

    protected PasswordResetCode() {}

    public PasswordResetCode(UUID userId, String codeHash, Instant expiresAt) {
        this.userId = userId;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
    }

    public String getCodeHash() { return codeHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean canAttempt(Instant now) { return attempts < 5 && now.isBefore(expiresAt); }
    public void recordFailure() { attempts++; }
}
