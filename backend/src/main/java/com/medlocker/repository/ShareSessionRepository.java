package com.medlocker.repository;

import com.medlocker.model.ShareSession;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.transaction.annotation.Transactional;

public interface ShareSessionRepository extends JpaRepository<ShareSession, UUID> {
    Optional<ShareSession> findByPinHashAndExpiresAtAfterAndRevokedFalse(String pinHash, Instant now);
    boolean existsByPinHashAndExpiresAtAndRevokedFalse(String pinHash, Instant expiresAt);

    @Modifying
    @Query("update ShareSession s set s.revoked = true where s.documentId = :id")
    void revokeForDocument(UUID id);

    @Transactional
    @Modifying
    @Query(value = """
            insert into share_sessions (id, document_id, pin_hash, expires_at, used, revoked)
            values (:id, :documentId, :pinHash, :expiresAt, false, false)
            on conflict (pin_hash) do nothing
            """, nativeQuery = true)
    int reserve(UUID id, UUID documentId, String pinHash, Instant expiresAt);

    @Transactional
    @Modifying
    @Query("update ShareSession s set s.used = true where s.pinHash = :pinHash and s.expiresAt = :expiresAt")
    void markUsed(String pinHash, Instant expiresAt);

    @Transactional
    @Modifying
    @Query("delete from ShareSession s where s.expiresAt <= :now")
    int purgeExpired(Instant now);
}
