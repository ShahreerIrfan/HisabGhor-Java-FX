package com.smartkhata.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Adaptive, cryptographically secure password and PIN hashing using PBKDF2WithHmacSHA256.
 * Generates unique salts per user and uses constant-time comparison against timing attacks.
 */
public final class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 100_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_LENGTH_BYTES = 16;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public record HashedCredential(String hashHex, String saltHex) {}

    private PasswordHasher() {}

    /**
     * Generates a new random salt and produces a PBKDF2WithHmacSHA256 hash.
     */
    public static HashedCredential hashPassword(char[] password) {
        Objects.requireNonNull(password, "Password cannot be null");
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        SECURE_RANDOM.nextBytes(salt);
        byte[] hash = computeHash(password, salt);
        return new HashedCredential(HexFormat.of().formatHex(hash), HexFormat.of().formatHex(salt));
    }

    /**
     * Verifies candidate password against stored hash using constant-time comparison.
     */
    public static boolean verifyPassword(char[] candidatePassword, String expectedHashHex, String saltHex) {
        if (candidatePassword == null || expectedHashHex == null || saltHex == null) {
            return false;
        }

        try {
            byte[] salt = HexFormat.of().parseHex(saltHex);
            byte[] expectedHash = HexFormat.of().parseHex(expectedHashHex);
            byte[] computedHash = computeHash(candidatePassword, salt);
            return MessageDigest.isEqual(expectedHash, computedHash);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] computeHash(char[] password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            return factory.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Cryptographic algorithm unavailable: " + ALGORITHM, e);
        }
    }
}

