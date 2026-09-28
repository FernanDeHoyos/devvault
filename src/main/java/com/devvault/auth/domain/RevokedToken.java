package com.devvault.auth.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "revoked_tokens")
public class RevokedToken {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected RevokedToken() {}

    public RevokedToken(String id, Instant expiresAt) {
        this.id = id;
        this.expiresAt = expiresAt;
    }

    public Instant getExpiresAt() { return expiresAt; }
}
