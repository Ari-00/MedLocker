package com.medlocker.service;

import com.medlocker.dto.Api;
import com.medlocker.model.PasswordResetCode;
import com.medlocker.repository.PasswordResetRepository;
import com.medlocker.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PasswordResetService {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final long CODE_LIFETIME_SECONDS = 600;
    private final SecureRandom random = new SecureRandom();
    private final UserRepository users;
    private final PasswordResetRepository codes;
    private final CryptoService crypto;
    private final PasswordEncoder passwords;
    private final ObjectProvider<JavaMailSender> mail;
    private final Executor executor;
    private final String from;
    private final String smtpHost;

    public PasswordResetService(UserRepository users, PasswordResetRepository codes, CryptoService crypto,
                                PasswordEncoder passwords, ObjectProvider<JavaMailSender> mail,
                                @Qualifier("applicationTaskExecutor") Executor executor,
                                @Value("${medlocker.mail-from}") String from,
                                @Value("${spring.mail.host}") String smtpHost) {
        this.users = users;
        this.codes = codes;
        this.crypto = crypto;
        this.passwords = passwords;
        this.mail = mail;
        this.executor = executor;
        this.from = from;
        this.smtpHost = smtpHost;
    }

    @Transactional
    public void request(String email) {
        JavaMailSender sender = mail.getIfAvailable();
        if (from.isBlank() || smtpHost.isBlank() || sender == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Password reset email is unavailable.");
        }
        users.findForPasswordReset(AccountService.normalize(email)).ifPresent(user -> {
            Instant now = Instant.now();
            var existing = codes.findById(user.getId());
            if (existing.isPresent() && existing.get().getExpiresAt().isAfter(now.plusSeconds(CODE_LIFETIME_SECONDS - 60))) return;
            String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
            codes.save(new PasswordResetCode(user.getId(), hash(user.getId(), code), now.plusSeconds(CODE_LIFETIME_SECONDS)));
            String destination = user.getEmail();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    try {
                        executor.execute(() -> send(sender, destination, code));
                    } catch (RuntimeException failure) {
                        log.warn("Could not queue a password reset email");
                    }
                }
            });
        });
    }

    @Transactional
    public boolean reset(Api.PasswordReset request) {
        if (!request.password().equals(request.confirmPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The new passwords do not match.");
        }
        AccountService.validatePasswordSize(request.password());
        var user = users.findForPasswordReset(AccountService.normalize(request.email())).orElse(null);
        if (user == null) return false;
        var code = codes.findById(user.getId()).orElse(null);
        if (code == null || !code.canAttempt(Instant.now())) return false;
        if (!MessageDigest.isEqual(code.getCodeHash().getBytes(StandardCharsets.US_ASCII),
                hash(user.getId(), request.code()).getBytes(StandardCharsets.US_ASCII))) {
            code.recordFailure();
            return false;
        }
        user.changePassword(passwords.encode(request.password()));
        codes.delete(code);
        return true;
    }

    private String hash(UUID id, String code) {
        return crypto.pinHash("password-reset:" + id + ":" + code);
    }

    private void send(JavaMailSender sender, String email, String code) {
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("Your MedLocker password reset code");
        message.setText("Your MedLocker password reset code is " + code
                + ". It expires in 10 minutes. If you did not request a reset, you can ignore this email.");
        try {
            sender.send(message);
        } catch (RuntimeException failure) {
            log.warn("Could not deliver a password reset email");
        }
    }
}
