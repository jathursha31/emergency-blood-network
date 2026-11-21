package com.blooddonor.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** One donor's involvement in one blood request. Also serves as the donation history. */
@Entity
@Table(name = "donor_responses", uniqueConstraints = {
        @UniqueConstraint(name = "uk_response_request_donor", columnNames = {"request_id", "donor_id"})
})
@Getter @Setter @NoArgsConstructor
public class DonorResponse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id")
    private BloodRequest request;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "donor_id")
    private Donor donor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ResponseStatus status = ResponseStatus.NOTIFIED;

    /** Distance from donor to hospital at the time of notification. */
    @Column(nullable = false)
    private double distanceKm;

    @Column(nullable = false, updatable = false)
    private LocalDateTime notifiedAt = LocalDateTime.now();

    private LocalDateTime respondedAt;
    private LocalDateTime donatedAt;
}
