package com.medlocker.service;

import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    public record Identity(UUID id, int passwordVersion) {}
    private final SecretKey key;
    private final JwtParser parser;

    public JwtService(@Value("${medlocker.jwt-secret}") String secret) {
        key = Keys.hmacShaKeyFor(CryptoService.decodeKey(secret, "MEDLOCKER_JWT_SECRET"));
        parser = Jwts.parser().verifyWith(key).requireIssuer("medlocker").build();
    }

    public String issue(UUID userId, int passwordVersion) {
        Instant now = Instant.now();
        return Jwts.builder().issuer("medlocker").subject(userId.toString()).id(UUID.randomUUID().toString())
                .claim("passwordVersion", passwordVersion)
                .issuedAt(Date.from(now)).expiration(Date.from(now.plus(1, ChronoUnit.HOURS)))
                .signWith(key, Jwts.SIG.HS256).compact();
    }

    public Identity authenticate(String token) {
        var claims = parser.parseSignedClaims(token).getPayload();
        Integer version = claims.get("passwordVersion", Integer.class);
        return new Identity(UUID.fromString(claims.getSubject()), version == null ? 0 : version);
    }
}
