package com.medlocker.service;

import io.minio.*;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class StorageService {
    public static final int MAX_PDF_BYTES = 10 * 1024 * 1024;
    private static final int MAX_ENCRYPTED_BYTES = MAX_PDF_BYTES + 16;
    private final MinioClient client;
    private final String bucket;
    private final boolean createBucket;

    public StorageService(@Value("${medlocker.minio.endpoint}") String endpoint,
                          @Value("${medlocker.minio.username}") String username,
                          @Value("${medlocker.minio.password}") String password,
                          @Value("${medlocker.minio.bucket}") String bucket,
                          @Value("${medlocker.minio.region}") String region,
                          @Value("${medlocker.minio.create-bucket}") boolean createBucket) {
        var builder = MinioClient.builder().endpoint(endpoint).credentials(username, password);
        if (!region.isBlank()) builder.region(region);
        this.client = builder.build();
        this.bucket = bucket;
        this.createBucket = createBucket;
    }

    @PostConstruct
    void initializeBucket() throws Exception {
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            if (!createBucket) throw new IllegalStateException("Create the configured storage bucket before starting MedLocker.");
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    public void put(String key, byte[] encrypted) {
        try {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                    .stream(new ByteArrayInputStream(encrypted), encrypted.length, -1)
                    .contentType("application/octet-stream").build());
        } catch (Exception failure) {
            throw new IllegalStateException("Encrypted object upload failed", failure);
        }
    }

    public byte[] get(String key) {
        try (var object = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            byte[] encrypted = object.readNBytes(MAX_ENCRYPTED_BYTES + 1);
            if (encrypted.length > MAX_ENCRYPTED_BYTES) {
                throw new IllegalStateException("Encrypted object exceeded the size limit");
            }
            return encrypted;
        } catch (Exception failure) {
            throw new IllegalStateException("Encrypted object retrieval failed", failure);
        }
    }

    public void remove(String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception failure) {
            throw new IllegalStateException("Encrypted object cleanup failed", failure);
        }
    }
}
