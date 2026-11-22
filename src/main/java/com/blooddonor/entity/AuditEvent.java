package com.blooddonor.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** One security-relevant event. Rows are only ever inserted, never changed. */
@Entity
@Table(name = "audit_events")
@Getter @Setter @NoArgsConstructor
public class AuditEvent {

    public enum Action {
        LOGIN, LOGIN_FAILED, OTP_LOGIN, LOGOUT, LOGOUT_ALL, TOKEN_REUSE_DETECTED,
        PASSWORD_CHANGED, PASSWORD_RESET, ACCOUNT_DISABLED, ACCOUNT_ENABLED,
        DONOR_REGISTERED, HOSPITAL_CREATED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime at;

    @Column(length = 60)
    private String actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Action action;

    @Column(length = 60)
    private String target;

    @Column(length = 300)
    private String details;

    @Column(length = 45)
    private String ip;
}
