package com.medlocker.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "medical_documents", indexes = @Index(columnList = "user_id, uploaded_at"))
public class MedicalDocument {
    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(nullable = false)
    private String fileName;
    @Column(nullable = false)
    private String contentType;
    @Column(nullable = false, unique = true)
    private String minioObjectKey;
    @Column(nullable = false)
    private String encryptedDek;
    @Column(nullable = false)
    private String iv;
    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    protected MedicalDocument() {}

    public MedicalDocument(UUID id, UUID userId, String contentType, String fileName, String encryptedDek, String iv) {
        this.id = id;
        this.userId = userId;
        this.fileName = fileName;
        this.contentType = contentType;
        this.minioObjectKey = id.toString();
        this.encryptedDek = encryptedDek;
        this.iv = iv;
        this.uploadedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public String getContentType() { return contentType; }
    public void replace(String name, String key, String dek, String iv) {
        this.fileName = name;
        this.minioObjectKey = key;
        this.encryptedDek = dek;
        this.iv = iv;
    }

    public UUID getId() { return id; }
    public String getFileName() { return fileName; }
    public String getMinioObjectKey() { return minioObjectKey; }
    public String getEncryptedDek() { return encryptedDek; }
    public String getIv() { return iv; }
    public Instant getUploadedAt() { return uploadedAt; }
}
