package com.medlocker.controller;

import com.medlocker.dto.Api;
import com.medlocker.repository.UserRepository;
import com.medlocker.service.SocialAccounts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth/social")
public class SocialAuthController {
    public static final String FLOW = SocialAuthController.class.getName() + ".flow";
    public static final String RESULT = SocialAuthController.class.getName() + ".result";
    private final ClientRegistrationRepository providers;
    private final UserRepository users;

    public SocialAuthController(ClientRegistrationRepository providers, UserRepository users) {
        this.providers = providers;
        this.users = users;
    }

    @GetMapping("/providers")
    public List<String> providers() {
        return List.of("google", "apple", "microsoft").stream()
                .filter(id -> providers.findByRegistrationId(id) != null).toList();
    }

    @PostMapping("/start")
    public Api.SocialRedirect start(@RequestHeader("X-MedLocker-Auth") String header, Principal principal,
                                    @Valid @RequestBody Api.SocialStart form, HttpServletRequest request) {
        requireHeader(header);
        if (providers.findByRegistrationId(form.provider()) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This sign-in provider is unavailable.");
        }
        UUID id = null;
        int version = 0;
        if (form.link()) {
            if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first.");
            id = UUID.fromString(principal.getName());
            var user = users.findById(id).orElseThrow();
            if (user.isDoctor() != form.doctor()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied.");
            version = user.getPasswordVersion();
        }
        var previous = request.getSession(false);
        if (previous != null) previous.invalidate();
        request.getSession().setAttribute(FLOW, new SocialAccounts.Flow(form.provider(), form.doctor(), id, version));
        return new Api.SocialRedirect("/oauth2/authorization/" + form.provider());
    }

    @PostMapping("/result")
    public ResponseEntity<Api.SocialResult> result(@RequestHeader("X-MedLocker-Auth") String header,
                                                   HttpServletRequest request) {
        requireHeader(header);
        var session = request.getSession(false);
        if (session == null) return ResponseEntity.noContent().build();
        synchronized (session) {
            if (!(session.getAttribute(RESULT) instanceof Api.SocialResult result)) {
                return ResponseEntity.noContent().build();
            }
            session.invalidate();
            return ResponseEntity.ok(result);
        }
    }

    private static void requireHeader(String header) {
        if (!"1".equals(header)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid sign-in request.");
        }
    }
}
