package com.medlocker.config;

import com.medlocker.dto.Api;
import com.medlocker.service.AccountService;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class DoctorProvisioning implements ApplicationRunner {
    private final AccountService accounts;
    private final Validator validator;
    private final Api.Register doctor;

    public DoctorProvisioning(AccountService accounts, Validator validator,
                              @Value("${medlocker.doctor-email}") String email,
                              @Value("${medlocker.doctor-password}") String password,
                              @Value("${medlocker.doctor-name}") String name) {
        this.accounts = accounts;
        this.validator = validator;
        this.doctor = new Api.Register(name.isBlank() ? "Clinic Doctor" : name, email, password);
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (doctor.email().isBlank() && doctor.password().isBlank()) return;
        if (!validator.validate(doctor).isEmpty()) {
            throw new IllegalStateException("Configure a valid doctor email, name and password.");
        }
        accounts.provisionDoctor(doctor.email(), doctor.password(), doctor.fullName());
    }
}
