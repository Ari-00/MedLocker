package com.medlocker.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "vault_users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false, unique = true, length = 254)
    private String email;
    @Column(nullable = false, length = 60)
    private String passwordHash;
    @Column(nullable = false, length = 120)
    private String fullName;
    @Column(nullable = false)
    private Instant createdAt;
    private Boolean doctor;
    private Integer passwordVersion;
    private LocalDate dateOfBirth;
    @Column(length = 40)
    private String phone;
    @Column(length = 250)
    private String address;
    @Column(length = 160)
    private String emergencyContact;
    @Column(length = 1000)
    private String allergies;

    protected User() {}

    public User(String email, String passwordHash, String fullName) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getFullName() { return fullName; }
    public boolean isDoctor() { return Boolean.TRUE.equals(doctor); }
    public int getPasswordVersion() { return passwordVersion == null ? 0 : passwordVersion; }
    public void changePassword(String hash) {
        passwordHash = hash;
        passwordVersion = getPasswordVersion() + 1;
    }
    public void designateDoctor() { doctor = true; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public String getPhone() { return phone; }
    public String getAddress() { return address; }
    public String getEmergencyContact() { return emergencyContact; }
    public String getAllergies() { return allergies; }

    public void updateProfile(String fullName, String email, LocalDate dateOfBirth, String phone,
                              String address, String emergencyContact, String allergies) {
        this.fullName = fullName;
        this.email = email;
        this.dateOfBirth = dateOfBirth;
        this.phone = phone;
        this.address = address;
        this.emergencyContact = emergencyContact;
        this.allergies = allergies;
    }
}
