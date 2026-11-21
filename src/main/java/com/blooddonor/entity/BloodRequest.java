package com.blooddonor.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "blood_requests", indexes = {
        @Index(name = "idx_request_status", columnList = "status")
})
@Getter @Setter @NoArgsConstructor
public class BloodRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id")
    private Hospital hospital;

    @Column(nullable = false, length = 100)
    private String patientName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private BloodGroup bloodGroup;

    @Column(nullable = false)
    private int unitsNeeded;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Urgency urgency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RequestStatus status = RequestStatus.OPEN;

    /** Current search radius. The scheduler widens it when not enough donors respond. */
    @Column(nullable = false)
    private double searchRadiusKm;

    @Column(nullable = false)
    private LocalDateTime neededBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** Last time donors were notified / radius was expanded. */
    private LocalDateTime lastSearchAt;

    private LocalDateTime closedAt;

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DonorResponse> responses = new ArrayList<>();

    public long countResponses(ResponseStatus responseStatus) {
        return responses.stream().filter(r -> r.getStatus() == responseStatus).count();
    }
}
