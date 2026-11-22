package com.blooddonor.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** A long-lived session token (sent as an httpOnly cookie). Only its SHA-256 hash is stored. */
@Entity
@Table(name = "refresh_tokens")
@Getter @Setter @NoArgsConstructor
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id")
    private UserAccount account;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** All tokens rotated from one login share a family id. */
    @Column(nullable = false, length = 36)
    private String family;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private LocalDateTime revokedAt;

    /** ROTATED, LOGOUT, LOGOUT_ALL, REUSE_DETECTED */
    @Column(length = 20)
    private String revokedReason;

    @Column(length = 200)
    private String userAgent;
}
