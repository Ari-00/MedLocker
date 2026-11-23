package com.medlocker.repository;

import com.medlocker.model.PasswordResetCode;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface PasswordResetRepository extends JpaRepository<PasswordResetCode, UUID> {
    @Transactional
    @Modifying
    @Query("delete from PasswordResetCode c where c.expiresAt <= :now")
    void purgeExpired(Instant now);
}
