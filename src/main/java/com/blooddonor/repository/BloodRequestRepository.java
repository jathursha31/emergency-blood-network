package com.blooddonor.repository;

import com.blooddonor.entity.BloodGroup;
import com.blooddonor.entity.BloodRequest;
import com.blooddonor.entity.RequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BloodRequestRepository extends JpaRepository<BloodRequest, Long> {

    /**
     * Row lock so two donors accepting at the same moment cannot both push
     * the request past unitsNeeded.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from BloodRequest r where r.id = :id")
    Optional<BloodRequest> findByIdForUpdate(@Param("id") Long id);

    List<BloodRequest> findByStatusOrderByCreatedAtDesc(RequestStatus status);

    List<BloodRequest> findAllByOrderByCreatedAtDesc();

    List<BloodRequest> findByHospitalIdOrderByCreatedAtDesc(Long hospitalId);

    List<BloodRequest> findByHospitalIdAndStatusOrderByCreatedAtDesc(Long hospitalId, RequestStatus status);

    List<BloodRequest> findByStatusInAndNeededByBefore(Collection<RequestStatus> statuses, LocalDateTime time);

    List<BloodRequest> findByStatusAndLastSearchAtBefore(RequestStatus status, LocalDateTime time);

    Optional<BloodRequest> findFirstByHospitalIdAndBloodGroupAndRestockTrueAndStatusIn(
            Long hospitalId, BloodGroup bloodGroup, Collection<RequestStatus> statuses);
}
