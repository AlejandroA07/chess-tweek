package com.chesstweek.identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class SessionCookieSealer {

    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final byte[] ASSOCIATED_DATA =
            "chess-tweek-session-v1".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,3072}");
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    private final Path keyPath;
    private final SecureRandom secureRandom;
    private byte[] cachedKey;

    SessionCookieSealer(Path keyPath, SecureRandom secureRandom) {
        this.keyPath = keyPath.toAbsolutePath().normalize();
        this.secureRandom = secureRandom;
    }

    String seal(String accessToken, long expiresAtEpochSecond) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            secureRandom.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(loadOrCreateKey(), "AES"),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD(ASSOCIATED_DATA);
            String payload = expiresAtEpochSecond + ":" + accessToken;
            byte[] ciphertext = cipher.doFinal(payload.getBytes(StandardCharsets.US_ASCII));

            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return "v1." + encoder.encodeToString(nonce) + "." + encoder.encodeToString(ciphertext);
        } catch (GeneralSecurityException | IOException exception) {
            throw new SessionSealingException(exception);
        }
    }

    Optional<OpenedSession> open(String sealedSession, Instant now) {
        if (sealedSession == null || sealedSession.length() > 4_096) {
            return Optional.empty();
        }
        String[] parts = sealedSession.split("\\.", -1);
        if (parts.length != 3 || !parts[0].equals("v1")) {
            return Optional.empty();
        }

        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] nonce = decoder.decode(parts[1]);
            byte[] ciphertext = decoder.decode(parts[2]);
            if (nonce.length != NONCE_BYTES || ciphertext.length < 16 || ciphertext.length > 3_200) {
                return Optional.empty();
            }

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(loadExistingKey(), "AES"),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD(ASSOCIATED_DATA);
            String payload = new String(cipher.doFinal(ciphertext), StandardCharsets.US_ASCII);
            int separator = payload.indexOf(':');
            if (separator < 1 || separator == payload.length() - 1) {
                return Optional.empty();
            }
            long expiresAt = Long.parseLong(payload.substring(0, separator));
            String accessToken = payload.substring(separator + 1);
            if (expiresAt <= now.getEpochSecond() || !TOKEN_PATTERN.matcher(accessToken).matches()) {
                return Optional.empty();
            }
            return Optional.of(new OpenedSession(accessToken, expiresAt));
        } catch (GeneralSecurityException | IOException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private synchronized byte[] loadOrCreateKey() throws IOException {
        if (cachedKey != null) {
            return cachedKey.clone();
        }

        prepareParentDirectory();
        if (Files.isSymbolicLink(keyPath)) {
            throw new IOException("Session key path must not be a symbolic link");
        }
        if (Files.notExists(keyPath, LinkOption.NOFOLLOW_LINKS)) {
            byte[] key = new byte[KEY_BYTES];
            secureRandom.nextBytes(key);
            Files.createFile(keyPath, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
            Files.write(keyPath, key, StandardOpenOption.WRITE);
        }
        if (!Files.isRegularFile(keyPath, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(keyPath).equals(FILE_PERMISSIONS)) {
            throw new IOException("Session key must be an owner-only regular file");
        }

        byte[] key = Files.readAllBytes(keyPath);
        if (key.length != KEY_BYTES) {
            throw new IOException("Session key has an invalid length");
        }
        cachedKey = key;
        return key.clone();
    }

    private synchronized byte[] loadExistingKey() throws IOException {
        if (cachedKey != null) {
            return cachedKey.clone();
        }
        if (Files.isSymbolicLink(keyPath)
                || !Files.isRegularFile(keyPath, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(keyPath).equals(FILE_PERMISSIONS)) {
            throw new IOException("Session key is unavailable");
        }
        byte[] key = Files.readAllBytes(keyPath);
        if (key.length != KEY_BYTES) {
            throw new IOException("Session key has an invalid length");
        }
        cachedKey = key;
        return key.clone();
    }

    private void prepareParentDirectory() throws IOException {
        Path parent = keyPath.getParent();
        if (parent == null) {
            throw new IOException("Session key must have a parent directory");
        }
        if (Files.isSymbolicLink(parent)) {
            throw new IOException("Session key directory must not be a symbolic link");
        }
        if (Files.notExists(parent, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(
                    parent, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
        }
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(parent).equals(DIRECTORY_PERMISSIONS)) {
            throw new IOException("Session key directory must be owner-only");
        }
    }

    record OpenedSession(String accessToken, long expiresAtEpochSecond) {}
}
