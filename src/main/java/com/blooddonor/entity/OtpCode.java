package com.blooddonor.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** A one-time code sent by SMS. Only a hash is stored. */
@Entity
@Table(name = "otp_codes", indexes = @Index(name = "idx_otp_phone_purpose", columnList = "phone, purpose, createdAt"))
@Getter @Setter @NoArgsConstructor
public class OtpCode {

    public enum Purpose { LOGIN, REGISTER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 15)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Purpose purpose;

    /** BCrypt hash of the 6-digit code. */
    @Column(nullable = false, length = 100)
    private String codeHash;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private boolean used;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
