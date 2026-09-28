package com.devvault.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Stores the single local administrator and JWT signing key outside the project checkout. */
@Component
public class LocalAuthStore {
    private static final String ENV_USERNAME = "DEVVAULT_AUTH_USERNAME";
    private static final String ENV_PASSWORD = "DEVVAULT_AUTH_PASSWORD";
    private static final String ENV_JWT_SECRET = "DEVVAULT_JWT_SECRET_BASE64";

    private final ObjectMapper objectMapper;
    private final PasswordEncoder passwordEncoder;
    private final Path configFile;
    private final String envUsername = System.getenv(ENV_USERNAME);
    private final String envPassword = System.getenv(ENV_PASSWORD);
    private final boolean environmentCredentialsConfigured;
    private volatile StoredConfig config;

    public LocalAuthStore(ObjectMapper objectMapper, PasswordEncoder passwordEncoder) {
        this.objectMapper = objectMapper;
        this.passwordEncoder = passwordEncoder;
        this.configFile = resolveConfigFile();
        this.environmentCredentialsConfigured = hasText(envUsername) && hasText(envPassword);
        if (hasText(envUsername) != hasText(envPassword)) {
            throw new IllegalStateException("Set both DEVVAULT_AUTH_USERNAME and DEVVAULT_AUTH_PASSWORD, or configure the administrator in the UI.");
        }
        this.config = loadOrCreateConfig();
        validateSecret(this.config.jwtSecretBase64());
    }

    public boolean setupRequired() {
        return !environmentCredentialsConfigured && !hasText(config.username());
    }

    public synchronized void createAdministrator(String username, String password, String recoveryKey) {
        if (environmentCredentialsConfigured) {
            throw new IllegalStateException("Administrator setup is disabled while environment credentials are configured.");
        }
        if (!setupRequired()) {
            throw new IllegalStateException("The local administrator has already been configured.");
        }
        config = new StoredConfig(username, passwordEncoder.encode(password), config.jwtSecretBase64(),
                passwordEncoder.encode(recoveryKey), config.tokenVersion());
        persist(config);
    }

    public synchronized boolean resetPassword(String username, String recoveryKey, String newPassword) {
        if (environmentCredentialsConfigured || setupRequired() || config.recoveryKeyHash() == null
                || !config.username().equals(username) || !passwordEncoder.matches(recoveryKey, config.recoveryKeyHash())) {
            return false;
        }
        config = new StoredConfig(config.username(), passwordEncoder.encode(newPassword), config.jwtSecretBase64(),
                config.recoveryKeyHash(), config.tokenVersion() + 1);
        persist(config);
        return true;
    }

    public long tokenVersion() {
        return config.tokenVersion();
    }

    public boolean recoveryConfigured() {
        return !environmentCredentialsConfigured && !setupRequired() && config.recoveryKeyHash() != null;
    }

    public String username() {
        return environmentCredentialsConfigured ? envUsername : config.username();
    }

    public boolean credentialsMatch(String username, String password) {
        if (setupRequired() || username == null || password == null) return false;
        if (environmentCredentialsConfigured) {
            return MessageDigest.isEqual(envUsername.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    username.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    && MessageDigest.isEqual(envPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return MessageDigest.isEqual(config.username().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                username.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                && passwordEncoder.matches(password, config.passwordHash());
    }

    public SecretKey signingKey() {
        String envSecret = System.getenv(ENV_JWT_SECRET);
        String value = hasText(envSecret) ? envSecret : config.jwtSecretBase64();
        return new SecretKeySpec(decodeSecret(value), "HmacSHA256");
    }

    private StoredConfig loadOrCreateConfig() {
        try {
            if (Files.exists(configFile)) return objectMapper.readValue(configFile.toFile(), StoredConfig.class);
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            StoredConfig initial = new StoredConfig(null, null, Base64.getEncoder().encodeToString(secret), null, 0);
            persist(initial);
            return initial;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read or create the DevVault auth config at " + configFile, exception);
        }
    }

    private synchronized void persist(StoredConfig value) {
        try {
            Path parent = configFile.getParent();
            Files.createDirectories(parent);
            restrictDirectoryPermissions(parent);
            Path tempFile = Files.createTempFile(parent, "auth-", ".tmp");
            try {
                objectMapper.writeValue(tempFile.toFile(), value);
                restrictFilePermissions(tempFile);
                try {
                    Files.move(tempFile, configFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException atomicMoveUnsupported) {
                    Files.move(tempFile, configFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not persist DevVault auth config at " + configFile, exception);
        }
    }

    private static Path resolveConfigFile() {
        String override = System.getenv("DEVVAULT_AUTH_CONFIG_PATH");
        if (hasText(override)) return Path.of(override).toAbsolutePath().normalize();
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            String appData = hasText(localAppData) ? localAppData : System.getenv("APPDATA");
            if (hasText(appData)) return Path.of(appData, "DevVault", "auth.json");
        }
        if (os.contains("mac")) return Path.of(System.getProperty("user.home"), "Library", "Application Support", "DevVault", "auth.json");
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path configHome = hasText(xdg) ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".config");
        return configHome.resolve("devvault").resolve("auth.json");
    }

    private static void validateSecret(String secret) {
        decodeSecret(secret);
    }

    private static byte[] decodeSecret(String value) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("DEVVAULT_JWT_SECRET_BASE64 must be valid Base64.");
        }
        if (bytes.length < 32) throw new IllegalStateException("JWT signing secret must contain at least 32 random bytes.");
        return bytes;
    }

    private static void restrictDirectoryPermissions(Path path) {
        setPosixPermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
    }

    private static void restrictFilePermissions(Path path) {
        setPosixPermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }

    private static void setPosixPermissions(Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows protects files under the user's LocalAppData with the user's inherited ACL.
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record StoredConfig(String username, String passwordHash, String jwtSecretBase64,
            String recoveryKeyHash, long tokenVersion) {}
}
