package com.medlocker.service;

import com.medlocker.dto.Api;
import com.medlocker.model.User;
import com.medlocker.repository.UserRepository;
import com.medlocker.repository.PasswordResetRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountService {
    private final UserRepository users;
    private final PasswordResetRepository resetCodes;
    private final PasswordEncoder passwords;
    private final JwtService jwt;
    private final String dummyHash;
    private final String doctorInviteCode;

    public AccountService(UserRepository users, PasswordResetRepository resetCodes, PasswordEncoder passwords, JwtService jwt,
                          @Value("${medlocker.doctor-invite-code}") String doctorInviteCode) {
        this.users = users;
        this.resetCodes = resetCodes;
        this.passwords = passwords;
        this.jwt = jwt;
        this.doctorInviteCode = doctorInviteCode;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public Api.Account register(Api.Register request) {
        return createAccount(request.email(), request.password(), request.fullName(), false);
    }

    public Api.Account registerDoctor(Api.DoctorRegister request) {
        if (doctorInviteCode.length() < 24 || doctorInviteCode.length() > 128 || doctorInviteCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Ask your clinic administrator to enable doctor registration.");
        }
        if (!MessageDigest.isEqual(doctorInviteCode.getBytes(StandardCharsets.UTF_8),
                request.inviteCode().getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The clinic invite code is incorrect.");
        }
        return createAccount(request.email(), request.password(), request.fullName(), true);
    }

    private Api.Account createAccount(String email, String password, String fullName, boolean doctor) {
        validatePasswordSize(password);
        var user = new User(normalize(email), passwords.encode(password), fullName.strip());
        if (doctor) user.designateDoctor();
        try {
            return account(users.saveAndFlush(user));
        } catch (DataIntegrityViolationException failure) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account already uses that email.");
        }
    }

    public Api.Account login(Api.Login request) {
        return login(request, false);
    }

    public Api.Account login(Api.Login request, boolean doctor) {
        validatePasswordSize(request.password());
        User user = users.findByEmail(normalize(request.email())).orElse(null);
        if (!passwords.matches(request.password(), user == null ? dummyHash : user.getPasswordHash())
                || user == null || user.isDoctor() != doctor) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Email or password is incorrect.");
        }
        return account(user);
    }

    public void provisionDoctor(String email, String password, String name) {
        var existing = users.findByEmail(normalize(email));
        if (existing.isPresent()) {
            if (!existing.get().isDoctor()) throw new IllegalStateException("Doctor email belongs to a patient.");
            return;
        }
        validatePasswordSize(password);
        var user = new User(normalize(email), passwords.encode(password), name.strip());
        user.designateDoctor();
        users.saveAndFlush(user);
    }

    public Api.Profile profile(UUID id) {
        return profileOf(find(id));
    }

    @Transactional
    public Api.Profile updateProfile(UUID id, Api.Profile profile) {
        User user = users.findForProfileUpdate(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to continue."));
        String email = normalize(profile.email());
        if (!user.getEmail().equals(email)) resetCodes.deleteById(id);
        user.updateProfile(profile.fullName().strip(), email, profile.dateOfBirth(),
                profile.phone(), profile.address(), profile.emergencyContact(), profile.allergies());
        try {
            return profileOf(users.saveAndFlush(user));
        } catch (DataIntegrityViolationException failure) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account already uses that email.");
        }
    }

    private User find(UUID id) {
        return users.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to continue."));
    }

    private static Api.Profile profileOf(User user) {
        return new Api.Profile(user.getFullName(), user.getEmail(), user.getDateOfBirth(), user.getPhone(),
                user.getAddress(), user.getEmergencyContact(), user.getAllergies());
    }

    public Api.Account account(User user) {
        return new Api.Account(jwt.issue(user.getId(), user.getPasswordVersion()), user.getEmail(), user.getFullName());
    }

    static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    static void validatePasswordSize(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72 || password.indexOf(0) >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Use a password up to 72 UTF-8 bytes with no null characters.");
        }
    }
}
