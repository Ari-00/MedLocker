package com.medlocker.controller;

import com.medlocker.dto.Api;
import com.medlocker.service.DocumentService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/share")
public class ShareController {
    private final DocumentService documents;

    public ShareController(DocumentService documents) {
        this.documents = documents;
    }

    @PostMapping(path = "/access", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void access(@Valid @RequestBody Api.Access request, HttpServletResponse response) throws IOException {
        var report = documents.access(request.pin());
        response.setHeader("X-Expires-At", report.expiresAt().toString());
        DocumentResponse.write(response, report.bytes(), MediaType.APPLICATION_PDF_VALUE);
    }
}
