package com.blooddonor.repository;

import com.blooddonor.entity.BloodGroup;
import com.blooddonor.entity.BloodStock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BloodStockRepository extends JpaRepository<BloodStock, Long> {

    List<BloodStock> findByHospitalId(Long hospitalId);

    /** Row lock so two "use 2 units" calls can't both pass the "enough stock?" check. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from BloodStock s where s.hospital.id = :hospitalId and s.bloodGroup = :group")
    Optional<BloodStock> findForUpdate(@Param("hospitalId") Long hospitalId, @Param("group") BloodGroup group);
}
