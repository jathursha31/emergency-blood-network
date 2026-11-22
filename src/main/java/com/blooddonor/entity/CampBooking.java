package com.blooddonor.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.LocalTime;

/** A donor's slot at a camp. The short code is shown as a QR code and scanned/typed at check-in. */
@Entity
@Table(name = "camp_bookings", indexes = @Index(name = "idx_booking_camp_slot", columnList = "camp_id, slotTime"))
@Getter @Setter @NoArgsConstructor
public class CampBooking {

    public enum Status { BOOKED, CANCELLED, ATTENDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "camp_id")
    private DonationCamp camp;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "donor_id")
    private Donor donor;

    @Column(nullable = false)
    private LocalTime slotTime;

    @Column(nullable = false, unique = true, length = 12)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.BOOKED;

    @Column(nullable = false, updatable = false)
    private LocalDateTime bookedAt = LocalDateTime.now();

    private LocalDateTime checkedInAt;
}
