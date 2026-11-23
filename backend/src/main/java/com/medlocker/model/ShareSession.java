package com.medlocker.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "share_sessions", indexes = @Index(columnList = "expires_at"))
public class ShareSession {
    @Id
    private UUID id;
    @Column(nullable = false)
    private UUID documentId;
    @Column(nullable = false, unique = true, length = 64)
    private String pinHash;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(nullable = false)
    private boolean used;
    @Column(nullable = false)
    private boolean revoked;

    protected ShareSession() {}

    public UUID getDocumentId() { return documentId; }
    public Instant getExpiresAt() { return expiresAt; }
}
