package com.medlocker.controller;

import com.medlocker.dto.Api;
import com.medlocker.service.AccountService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {
    private final AccountService accounts;

    public ProfileController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    public Api.Profile get(Principal principal) {
        return accounts.profile(UUID.fromString(principal.getName()));
    }

    @PutMapping
    public Api.Profile update(Principal principal, @Valid @RequestBody Api.Profile profile) {
        return accounts.updateProfile(UUID.fromString(principal.getName()), profile);
    }
}
