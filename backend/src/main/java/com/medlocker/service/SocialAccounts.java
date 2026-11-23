package com.medlocker.service;

import com.medlocker.dto.Api;
import com.medlocker.model.SocialIdentity;
import com.medlocker.repository.SocialIdentityRepository;
import com.medlocker.repository.UserRepository;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class SocialAccounts {
    private final SocialIdentityRepository identities;
    private final UserRepository users;
    private final AccountService accounts;

    public SocialAccounts(SocialIdentityRepository identities, UserRepository users, AccountService accounts) {
        this.identities = identities;
        this.users = users;
        this.accounts = accounts;
    }

    public record Flow(String provider, boolean doctor, UUID userId, int passwordVersion) {
        public String path() { return doctor ? "/viewer.html" : "/"; }
    }

    public Api.SocialResult complete(Flow flow, String subject) {
        if (subject == null || subject.isBlank() || subject.length() > 255) {
            return new Api.SocialResult(null, "The provider did not return a valid identity.");
        }
        var existing = identities.findByProviderAndSubject(flow.provider(), subject);
        UUID id = flow.userId() == null ? existing.map(SocialIdentity::getUserId).orElse(null) : flow.userId();
        if (id == null) {
            return new Api.SocialResult(null, "Sign in with your MedLocker password first, then connect this provider.");
        }
        var user = users.findById(id).orElse(null);
        if (user == null || user.isDoctor() != flow.doctor()) {
            return new Api.SocialResult(null, "Use the MedLocker portal that matches this account.");
        }
        if (flow.userId() != null) {
            if (user.getPasswordVersion() != flow.passwordVersion()) {
                return new Api.SocialResult(null, "Sign in again before connecting a provider.");
            }
            if (existing.isPresent() && !existing.get().getUserId().equals(id)) {
                return new Api.SocialResult(accounts.account(user), "This provider account is already connected.");
            }
            if (existing.isEmpty()) {
                try {
                    identities.saveAndFlush(new SocialIdentity(flow.provider(), subject, id));
                } catch (DataIntegrityViolationException failure) {
                    return new Api.SocialResult(accounts.account(user), "A provider account is already connected.");
                }
            }
        }
        return new Api.SocialResult(accounts.account(user), flow.userId() == null ? null : "Sign-in provider connected.");
    }
}
