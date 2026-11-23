package com.medlocker.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CryptoService {
    public record Encrypted(byte[] ciphertext, String wrappedKey, String iv) {}

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec masterKey;
    private final SecretKeySpec pepper;

    public CryptoService(@Value("${medlocker.master-key}") String masterKey,
                         @Value("${medlocker.pin-pepper}") String pepper) {
        this.masterKey = new SecretKeySpec(decodeKey(masterKey, "MEDLOCKER_MASTER_KEY"), "AES");
        this.pepper = new SecretKeySpec(decodeKey(pepper, "MEDLOCKER_PIN_PEPPER"), "HmacSHA256");
    }

    public Encrypted encrypt(UUID id, byte[] plaintext) {
        byte[] dek = null;
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256, random);
            dek = generator.generateKey().getEncoded();
            byte[] fileIv = newIv();
            byte[] wrappingIv = newIv();
            byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"), fileIv, id, plaintext);
            byte[] wrapped = crypt(Cipher.ENCRYPT_MODE, masterKey, wrappingIv, id, dek);
            byte[] wrappedWithIv = Arrays.copyOf(wrappingIv, IV_BYTES + wrapped.length);
            System.arraycopy(wrapped, 0, wrappedWithIv, IV_BYTES, wrapped.length);
            return new Encrypted(ciphertext, encode(wrappedWithIv), encode(fileIv));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Document encryption failed", failure);
        } finally {
            if (dek != null) Arrays.fill(dek, (byte) 0);
        }
    }

    public byte[] decrypt(UUID id, byte[] ciphertext, String wrappedKey, String iv) {
        byte[] dek = null;
        try {
            byte[] stored = Base64.getDecoder().decode(wrappedKey);
            byte[] wrappingIv = Arrays.copyOf(stored, IV_BYTES);
            byte[] wrapped = Arrays.copyOfRange(stored, IV_BYTES, stored.length);
            dek = crypt(Cipher.DECRYPT_MODE, masterKey, wrappingIv, id, wrapped);
            return crypt(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"),
                    Base64.getDecoder().decode(iv), id, ciphertext);
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw new IllegalStateException("Document authentication or decryption failed", failure);
        } finally {
            if (dek != null) Arrays.fill(dek, (byte) 0);
        }
    }

    public String pinHash(String pin) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(pepper);
            return HexFormat.of().formatHex(mac.doFinal(pin.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("PIN hashing failed", failure);
        }
    }

    static byte[] decodeKey(String encoded, String variable) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(variable + " must encode exactly 32 bytes in Base64.");
        }
        if (key.length != 32) {
            throw new IllegalArgumentException(variable + " must encode exactly 32 bytes in Base64.");
        }
        return key;
    }

    private byte[] newIv() {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        return iv;
    }

    private static byte[] crypt(int mode, SecretKeySpec key, byte[] iv, UUID id, byte[] input)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(id.toString().getBytes(StandardCharsets.US_ASCII));
        return cipher.doFinal(input);
    }

    private static String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
