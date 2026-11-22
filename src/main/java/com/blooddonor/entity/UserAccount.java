package com.blooddonor.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Login account. A DONOR account links to a Donor, a HOSPITAL account links to a Hospital,
 * an ADMIN account links to neither.
 */
@Entity
@Table(name = "user_accounts")
@Getter @Setter @NoArgsConstructor
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 60)
    private String username;

    /** BCrypt hash, never the plain password. */
    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Role role;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "donor_id", unique = true)
    private Donor donor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id")
    private Hospital hospital;

    @Column(nullable = false)
    private boolean enabled = true;

    /** Copied into every access token ("tv" claim). Bumping it invalidates all of them at once. */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int tokenVersion;

    private LocalDateTime lastLoginAt;

    /** Consecutive failed logins since the last success (brute-force protection). */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int failedLogins;

    private LocalDateTime lastFailedLoginAt;

    /** Login refused until this time after too many failures. */
    private LocalDateTime lockedUntil;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
