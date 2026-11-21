package com.blooddonor.repository;

import com.blooddonor.entity.BloodGroup;
import com.blooddonor.entity.Donor;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DonorRepository extends JpaRepository<Donor, Long> {

    boolean existsByPhone(String phone);

    /** Row lock so the same donor accepting two different requests at once is serialized. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Donor d where d.id = :id")
    Optional<Donor> findByIdForUpdate(@Param("id") Long id);

    /**
     * Eligible donors inside a lat/lng rectangle:
     * compatible blood group, available, last donation on/before the cutoff,
     * and age inside the allowed range (born between minDob and maxDob).
     */
    @Query("""
            select d from Donor d
            where d.bloodGroup in :groups
              and d.available = true
              and (d.lastDonationDate is null or d.lastDonationDate <= :lastDonationCutoff)
              and d.dateOfBirth between :minDob and :maxDob
              and d.latitude between :minLat and :maxLat
              and d.longitude between :minLng and :maxLng
            """)
    List<Donor> findEligibleInBox(@Param("groups") Collection<BloodGroup> groups,
                                  @Param("lastDonationCutoff") LocalDate lastDonationCutoff,
                                  @Param("minDob") LocalDate minDob,
                                  @Param("maxDob") LocalDate maxDob,
                                  @Param("minLat") double minLat,
                                  @Param("maxLat") double maxLat,
                                  @Param("minLng") double minLng,
                                  @Param("maxLng") double maxLng);

    /** Donors whose waiting period ends exactly today, for the "you can donate again" reminder. */
    List<Donor> findByAvailableTrueAndLastDonationDate(LocalDate lastDonationDate);
}
