package com.blooddonor.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Units of one blood group in one hospital's blood bank. */
@Entity
@Table(name = "blood_stock", uniqueConstraints =
        @UniqueConstraint(name = "uk_stock_hospital_group", columnNames = {"hospital_id", "bloodGroup"}))
@Getter @Setter @NoArgsConstructor
public class BloodStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id")
    private Hospital hospital;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private BloodGroup bloodGroup;

    @Column(nullable = false)
    private int units;

    /** Alert level. 0 = no alert. Below this, a restock request can be raised automatically. */
    @Column(nullable = false)
    private int minUnits;

    /** When true, falling below minUnits raises a restock request to nearby donors. */
    @Column(nullable = false)
    private boolean autoRequest = true;

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public boolean isLow() {
        return minUnits > 0 && units < minUnits;
    }
}
