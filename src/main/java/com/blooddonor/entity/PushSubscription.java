package com.blooddonor.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** A browser/device that agreed to receive Web Push notifications for an account. */
@Entity
@Table(name = "push_subscriptions")
@Getter @Setter @NoArgsConstructor
public class PushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id")
    private UserAccount account;

    /** The push service URL for this browser (FCM, Mozilla autopush, Apple...). */
    @Column(nullable = false, unique = true, length = 1000)
    private String endpoint;

    /** Browser's P-256 public key, base64url. */
    @Column(nullable = false, length = 200)
    private String p256dh;

    /** Browser's 16-byte auth secret, base64url. */
    @Column(nullable = false, length = 100)
    private String auth;

    @Column(length = 200)
    private String userAgent;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime lastSuccessAt;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int failures;
}
