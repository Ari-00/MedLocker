package com.medlocker.repository;

import com.medlocker.model.MedicalDocument;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<MedicalDocument, UUID> {
    List<MedicalDocument> findAllByUserIdAndContentTypeOrderByUploadedAtDesc(UUID userId, String type);
    List<MedicalDocument> findAllByUserIdAndContentTypeNotOrderByUploadedAtDesc(UUID userId, String type);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MedicalDocument> findByIdAndUserId(UUID id, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select d from MedicalDocument d where d.id = :id")
    Optional<MedicalDocument> findForReading(UUID id);
}
