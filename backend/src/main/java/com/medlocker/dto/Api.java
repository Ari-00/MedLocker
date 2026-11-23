package com.medlocker.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class Api {
    private Api() {}

    public record Register(@NotBlank @Size(max = 120) String fullName,
                           @NotBlank @Email @Size(max = 254) String email,
                           @NotBlank @Size(min = 8, max = 72) String password) {}
    public record Login(@NotBlank @Email @Size(max = 254) String email,
                        @NotBlank @Size(max = 72) String password) {}
    public record DoctorRegister(@NotBlank @Size(max = 120) String fullName,
                                 @NotBlank @Email @Size(max = 254) String email,
                                 @NotBlank @Size(min = 8, max = 72) String password,
                                 @NotBlank @Size(max = 128) String inviteCode) {}
    public record Account(String token, String email, String fullName) {}
    public record ResetRequest(@NotBlank @Email @Size(max = 254) String email) {}
    public record PasswordReset(@NotBlank @Email @Size(max = 254) String email,
                                @NotNull @Pattern(regexp = "[0-9]{6}") String code,
                                @NotBlank @Size(min = 8, max = 72) String password,
                                @NotBlank @Size(min = 8, max = 72) String confirmPassword) {}
    public record SocialStart(@NotBlank String provider, boolean doctor, boolean link) {}
    public record SocialRedirect(String url) {}
    public record SocialResult(Api.Account account, String message) {}
    public record Profile(@NotBlank @Size(max = 120) String fullName,
                          @NotBlank @Email @Size(max = 254) String email,
                          @PastOrPresent LocalDate dateOfBirth,
                          @Size(max = 40) String phone, @Size(max = 250) String address,
                          @Size(max = 160) String emergencyContact, @Size(max = 1000) String allergies) {}
    public record Document(UUID id, String fileName, Instant uploadedAt) {}
    public record Share(String pin, Instant expiresAt) {}
    public record Access(@NotNull @Pattern(regexp = "[0-9]{6}") String pin) {}
    public record Error(Instant timestamp, int status, String error, String message) {}
}
