package com.medlocker.controller;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import org.springframework.http.HttpHeaders;

final class DocumentResponse {
    private DocumentResponse() {}

    static void write(HttpServletResponse response, byte[] bytes, String type) throws IOException {
        try {
            response.setContentType(type);
            response.setContentLength(bytes.length);
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate");
            response.getOutputStream().write(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
