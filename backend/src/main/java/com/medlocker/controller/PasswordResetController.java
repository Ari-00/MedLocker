package com.medlocker.controller;

import com.medlocker.dto.Api;
import com.medlocker.service.PasswordResetService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(path = "/api/v1/auth/password", consumes = MediaType.APPLICATION_JSON_VALUE)
public class PasswordResetController {
    private final PasswordResetService passwords;

    public PasswordResetController(PasswordResetService passwords) {
        this.passwords = passwords;
    }

    @PostMapping("/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void request(@Valid @RequestBody Api.ResetRequest request) {
        passwords.request(request.email());
    }

    @PostMapping("/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@Valid @RequestBody Api.PasswordReset request) {
        if (!passwords.reset(request)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The code is invalid or expired. Request a new code.");
        }
    }
}
