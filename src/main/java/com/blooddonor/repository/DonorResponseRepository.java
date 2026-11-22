package com.blooddonor.repository;

import com.blooddonor.entity.DonorResponse;
import com.blooddonor.entity.RequestStatus;
import com.blooddonor.entity.ResponseStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DonorResponseRepository extends JpaRepository<DonorResponse, Long> {

    /** The donor's accepted responses on requests that are still active, excluding one request. */
    List<DonorResponse> findByDonorIdAndStatusAndRequestIdNotAndRequestStatusIn(
            Long donorId, ResponseStatus status, Long excludeRequestId, Collection<RequestStatus> requestStatuses);

    Optional<DonorResponse> findByRequestIdAndDonorId(Long requestId, Long donorId);

    List<DonorResponse> findByDonorIdAndStatusOrderByDonatedAtDesc(Long donorId, ResponseStatus status);

    List<DonorResponse> findByDonorIdOrderByNotifiedAtDesc(Long donorId);

    long countByDonorIdAndStatus(Long donorId, ResponseStatus status);

    /** [donorId, count] for every donor with at least one response in this status. */
    @Query("select r.donor.id, count(r) from DonorResponse r where r.status = :status group by r.donor.id")
    List<Object[]> countByDonorGrouped(@Param("status") ResponseStatus status);
}
