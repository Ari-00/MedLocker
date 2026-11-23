package com.medlocker.repository;

import com.medlocker.model.SocialIdentity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SocialIdentityRepository extends JpaRepository<SocialIdentity, UUID> {
    Optional<SocialIdentity> findByProviderAndSubject(String provider, String subject);
}
