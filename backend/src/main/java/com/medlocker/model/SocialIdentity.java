package com.medlocker.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "social_identities", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"provider", "subject"}),
        @UniqueConstraint(columnNames = {"provider", "user_id"})
})
public class SocialIdentity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false, length = 20)
    private String provider;
    @Column(nullable = false, length = 255)
    private String subject;
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    protected SocialIdentity() {}

    public SocialIdentity(String provider, String subject, UUID userId) {
        this.provider = provider;
        this.subject = subject;
        this.userId = userId;
    }

    public UUID getUserId() { return userId; }
}
