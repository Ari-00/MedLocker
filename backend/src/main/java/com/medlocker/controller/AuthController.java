package com.medlocker.controller;

import com.medlocker.dto.Api;
import com.medlocker.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(path = "/api/v1/auth", consumes = MediaType.APPLICATION_JSON_VALUE)
public class AuthController {
    private final AccountService accounts;

    public AuthController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping("/register")
    public Api.Account register(@Valid @RequestBody Api.Register request) {
        return accounts.register(request);
    }

    @PostMapping("/login")
    public Api.Account login(@Valid @RequestBody Api.Login request) {
        return accounts.login(request);
    }

    @PostMapping("/doctor/login")
    public Api.Account doctorLogin(@Valid @RequestBody Api.Login request) {
        return accounts.login(request, true);
    }

    @PostMapping("/doctor/register")
    public Api.Account doctorRegister(@Valid @RequestBody Api.DoctorRegister request) {
        return accounts.registerDoctor(request);
    }
}
