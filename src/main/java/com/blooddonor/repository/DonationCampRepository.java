package com.blooddonor.repository;

import com.blooddonor.entity.DonationCamp;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DonationCampRepository extends JpaRepository<DonationCamp, Long> {

    List<DonationCamp> findByStatusAndCampDateGreaterThanEqualOrderByCampDateAscStartTimeAsc(
            DonationCamp.Status status, LocalDate from);

    List<DonationCamp> findByHospitalIdOrderByCampDateDesc(Long hospitalId);

    List<DonationCamp> findAllByOrderByCampDateDesc();

    /** Row lock while booking, so two donors can't take the last place in a slot at the same time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from DonationCamp c where c.id = :id")
    Optional<DonationCamp> findByIdForUpdate(@Param("id") Long id);
}
