package com.medlocker.controller;

import com.medlocker.dto.Api;
import com.medlocker.service.DocumentService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {
    private final DocumentService documents;

    public DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Api.Document upload(Principal principal, @RequestParam("file") MultipartFile file) throws IOException {
        return documents.upload(UUID.fromString(principal.getName()), file);
    }

    @GetMapping
    public List<Api.Document> list(Principal principal) {
        return documents.list(UUID.fromString(principal.getName()));
    }

    @PostMapping("/{id}/share")
    public Api.Share share(Principal principal, @PathVariable UUID id) {
        return documents.share(UUID.fromString(principal.getName()), id);
    }

    @GetMapping("/photos")
    public List<Api.Document> gallery(Principal principal) {
        return documents.gallery(UUID.fromString(principal.getName()));
    }

    @PostMapping(path = "/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Api.Document uploadPhoto(Principal principal, @RequestParam("file") MultipartFile file) throws IOException {
        return documents.upload(UUID.fromString(principal.getName()), file, true);
    }

    @GetMapping("/{id}/content")
    public void content(Principal principal, @PathVariable UUID id, HttpServletResponse response) throws IOException {
        var content = documents.content(UUID.fromString(principal.getName()), id);
        DocumentResponse.write(response, content.bytes(), content.type());
    }

    @PutMapping(path = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Api.Document replace(Principal principal, @PathVariable UUID id,
                                @RequestParam("file") MultipartFile file) throws IOException {
        return documents.replace(UUID.fromString(principal.getName()), id, file);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Principal principal, @PathVariable UUID id) {
        documents.delete(UUID.fromString(principal.getName()), id);
        return ResponseEntity.noContent().build();
    }
}
