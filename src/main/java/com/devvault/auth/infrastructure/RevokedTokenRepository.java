package com.devvault.auth.infrastructure;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;

import com.devvault.auth.domain.RevokedToken;

public interface RevokedTokenRepository extends JpaRepository<RevokedToken, String> {
    void deleteByExpiresAtBefore(Instant instant);
}
