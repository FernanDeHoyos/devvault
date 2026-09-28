package com.devvault.auth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devvault.auth.domain.RevokedToken;
import com.devvault.auth.dto.LoginRequest;
import com.devvault.auth.dto.PasswordRecoveryRequest;
import com.devvault.auth.dto.TokenResponse;
import com.devvault.auth.infrastructure.RevokedTokenRepository;
import com.devvault.shared.api.exception.ApiException;

@Service
public class AuthService {
    private final AuthProperties properties;
    private final LocalAuthStore authStore;
    private final JwtEncoder jwtEncoder;
    private final RevokedTokenRepository revokedTokens;

    public AuthService(AuthProperties properties, LocalAuthStore authStore, JwtEncoder jwtEncoder, RevokedTokenRepository revokedTokens) {
        this.properties = properties;
        this.authStore = authStore;
        this.jwtEncoder = jwtEncoder;
        this.revokedTokens = revokedTokens;
    }

    public TokenResponse login(LoginRequest request) {
        if (!authStore.credentialsMatch(request.username(), request.password())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Usuario o contraseña inválidos.");
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.tokenDurationMinutes(), ChronoUnit.MINUTES);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("devvault")
                .subject(authStore.username())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("scope", "api")
                .claim("authVersion", authStore.tokenVersion())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new TokenResponse(token, "Bearer", expiresAt, authStore.username());
    }

    public boolean setupRequired() {
        return authStore.setupRequired();
    }

    public boolean recoveryConfigured() {
        return authStore.recoveryConfigured();
    }

    public void createAdministrator(String username, String password, String recoveryKey) {
        try {
            authStore.createAdministrator(username, password, recoveryKey);
        } catch (IllegalStateException exception) {
            throw new ApiException(HttpStatus.CONFLICT, exception.getMessage());
        }
    }

    public void recoverPassword(PasswordRecoveryRequest request) {
        if (!authStore.resetPassword(request.username(), request.recoveryKey(), request.newPassword())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "No se pudo recuperar el acceso con esos datos.");
        }
    }

    @Transactional
    public void logout(String tokenId, Instant expiresAt) {
        if (tokenId == null || expiresAt == null || !expiresAt.isAfter(Instant.now())) return;
        revokedTokens.deleteByExpiresAtBefore(Instant.now());
        if (!revokedTokens.existsById(tokenId)) revokedTokens.save(new RevokedToken(tokenId, expiresAt));
    }
}
