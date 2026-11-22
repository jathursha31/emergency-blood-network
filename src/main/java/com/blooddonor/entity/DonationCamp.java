package com.blooddonor.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** A blood donation camp run by a hospital: one day, split into fixed-length slots with a capacity each. */
@Entity
@Table(name = "donation_camps", indexes = @Index(name = "idx_camp_date", columnList = "campDate"))
@Getter @Setter @NoArgsConstructor
public class DonationCamp {

    public enum Status { ACTIVE, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id")
    private Hospital hospital;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 250)
    private String address;

    @Column(length = 60)
    private String city;

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    @Column(nullable = false)
    private LocalDate campDate;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    @Column(nullable = false)
    private int slotMinutes;

    @Column(nullable = false)
    private int capacityPerSlot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.ACTIVE;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @OneToMany(mappedBy = "camp")
    private List<CampBooking> bookings = new ArrayList<>();

    /**
     * Slot start times: start, start+slot, ... while the slot still ends by endTime.
     * Works in minutes-of-day, because LocalTime.plusMinutes wraps around midnight (23:30 + 30 = 00:00).
     */
    public List<LocalTime> slotTimes() {
        List<LocalTime> slots = new ArrayList<>();
        if (slotMinutes <= 0) return slots;
        int start = startTime.toSecondOfDay() / 60;
        int end = endTime.toSecondOfDay() / 60;
        for (int m = start; m + slotMinutes <= end; m += slotMinutes) {
            slots.add(LocalTime.of(m / 60, m % 60));
        }
        return slots;
    }
}
