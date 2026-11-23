package com.medlocker.service;

import com.medlocker.dto.Api;
import com.medlocker.model.MedicalDocument;
import com.medlocker.repository.DocumentRepository;
import com.medlocker.repository.ShareSessionRepository;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DocumentService {
    private static final String PDF = "application/pdf";
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DocumentService.class);
    private record ProtectedFile(String name, String type, CryptoService.Encrypted data) {}
    public record Content(byte[] bytes, String type) {}
    public record Report(byte[] bytes, Instant expiresAt) {}

    private final DocumentRepository documents;
    private final ShareSessionRepository shares;
    private final StorageService storage;
    private final CryptoService protection;
    private final SecureRandom random = new SecureRandom();

    public DocumentService(DocumentRepository documents, ShareSessionRepository shares,
                           StorageService storage, CryptoService protection) {
        this.documents = documents;
        this.shares = shares;
        this.storage = storage;
        this.protection = protection;
    }

    public Api.Document upload(UUID userId, MultipartFile file) throws IOException {
        return upload(userId, file, false);
    }

    public Api.Document upload(UUID userId, MultipartFile file, boolean photo) throws IOException {
        UUID id = UUID.randomUUID();
        var incoming = protect(id, file, photo);
        var encrypted = incoming.data();
        var document = new MedicalDocument(id, userId, incoming.type(), incoming.name(), encrypted.wrappedKey(), encrypted.iv());
        storage.put(document.getMinioObjectKey(), encrypted.ciphertext());
        try {
            return summary(documents.saveAndFlush(document));
        } catch (RuntimeException failure) {
            removeObject(document.getMinioObjectKey());
            throw failure;
        }
    }

    private ProtectedFile protect(UUID id, MultipartFile file, boolean photo) throws IOException {
        if (file.getSize() > StorageService.MAX_PDF_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Choose a file up to 10 MB.");
        }
        String name = file.getOriginalFilename();
        if (name == null) throw invalidFile(photo);
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.length() > 255 || name.chars().anyMatch(Character::isISOControl)
                || name.isEmpty()) throw invalidFile(photo);

        byte[] plaintext = file.getBytes();
        try {
            String type = mediaType(plaintext, name, photo);
            return new ProtectedFile(name, type, protection.encrypt(id, plaintext));
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public List<Api.Document> list(UUID userId) {
        return documents.findAllByUserIdAndContentTypeOrderByUploadedAtDesc(userId, PDF).stream()
                .map(DocumentService::summary).toList();
    }

    public List<Api.Document> gallery(UUID userId) {
        return documents.findAllByUserIdAndContentTypeNotOrderByUploadedAtDesc(userId, PDF).stream()
                .map(DocumentService::summary).toList();
    }

    @Transactional
    public Content content(UUID userId, UUID id) {
        var document = owned(userId, id);
        return new Content(read(document), document.getContentType());
    }

    @Transactional(rollbackFor = IOException.class)
    public Api.Document replace(UUID userId, UUID id, MultipartFile file) throws IOException {
        var document = owned(userId, id);
        if (!PDF.equals(document.getContentType())) throw notFound();
        var incoming = protect(id, file, false);
        String newKey = UUID.randomUUID().toString();
        scheduleCleanup(document.getMinioObjectKey(), newKey);
        var encrypted = incoming.data();
        storage.put(newKey, encrypted.ciphertext());
        document.replace(incoming.name(), newKey, encrypted.wrappedKey(), encrypted.iv());
        shares.revokeForDocument(id);
        return summary(document);
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        var document = owned(userId, id);
        shares.revokeForDocument(id);
        documents.delete(document);
        scheduleCleanup(document.getMinioObjectKey(), null);
    }

    private void scheduleCleanup(String oldKey, String newKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) removeObject(oldKey);
                if (status == STATUS_ROLLED_BACK && newKey != null) removeObject(newKey);
            }
        });
    }

    private void removeObject(String key) {
        try {
            storage.remove(key);
        } catch (RuntimeException failure) {
            log.atError().addKeyValue("object_key", key).log("Encrypted object cleanup failed");
        }
    }

    @Transactional
    public Api.Share share(UUID userId, UUID id) {
        if (!PDF.equals(owned(userId, id).getContentType())) throw notFound();
        Instant expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
        for (int attempt = 0; attempt < 12; attempt++) {
            String pin = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
            if (shares.reserve(UUID.randomUUID(), id, protection.pinHash(pin), expiresAt) == 1) {
                return new Api.Share(pin, expiresAt);
            }
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Try generating a PIN again.");
    }

    @Transactional
    public Report access(String pin) {
        String hash = protection.pinHash(pin);
        var share = shares.findByPinHashAndExpiresAtAfterAndRevokedFalse(hash, Instant.now())
                .orElseThrow(DocumentService::invalidPin);
        var document = documents.findForReading(share.getDocumentId()).orElseThrow(DocumentService::invalidPin);
        if (!shares.existsByPinHashAndExpiresAtAndRevokedFalse(hash, share.getExpiresAt())
                || !share.getExpiresAt().isAfter(Instant.now())) throw invalidPin();
        shares.markUsed(hash, share.getExpiresAt());
        byte[] plaintext = read(document);
        return new Report(plaintext, share.getExpiresAt());
    }

    private static Api.Document summary(MedicalDocument document) {
        return new Api.Document(document.getId(), document.getFileName(), document.getUploadedAt());
    }

    private byte[] read(MedicalDocument document) {
        return protection.decrypt(document.getId(), storage.get(document.getMinioObjectKey()),
                document.getEncryptedDek(), document.getIv());
    }

    private MedicalDocument owned(UUID userId, UUID id) {
        return documents.findByIdAndUserId(id, userId).orElseThrow(DocumentService::notFound);
    }

    private static String mediaType(byte[] bytes, String name, boolean photo) {
        if (!photo && name.toLowerCase(Locale.ROOT).endsWith(".pdf")
                && startsWith(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII))) return PDF;
        if (photo && startsWith(bytes, new byte[]{(byte) 255, (byte) 216, (byte) 255})) {
            validateImage(bytes, "JPEG");
            return "image/jpeg";
        }
        if (photo && startsWith(bytes, new byte[]{(byte) 137, 'P', 'N', 'G', 13, 10, 26, 10})) {
            validateImage(bytes, "PNG");
            return "image/png";
        }
        throw invalidFile(photo);
    }

    private static void validateImage(byte[] bytes, String format) {
        var reader = ImageIO.getImageReadersByFormatName(format).next();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            reader.setInput(input, true, true);
            var params = reader.getDefaultReadParam();
            int horizontalStep = Math.max(1, reader.getWidth(0) / 128);
            int verticalStep = Math.max(1, reader.getHeight(0) / 128);
            params.setSourceSubsampling(horizontalStep, verticalStep, 0, 0);
            reader.read(0, params).flush();
        } catch (IOException | IllegalArgumentException failure) {
            throw invalidFile(true);
        } finally {
            reader.dispose();
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] signature) {
        return bytes.length > signature.length
                && Arrays.equals(bytes, 0, signature.length, signature, 0, signature.length);
    }

    private static ResponseStatusException invalidFile(boolean photo) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                photo ? "Choose a JPEG or PNG photo." : "Choose a valid PDF file.");
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found.");
    }

    private static ResponseStatusException invalidPin() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "PIN is invalid or expired.");
    }
}
