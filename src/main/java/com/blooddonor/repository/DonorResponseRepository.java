package com.blooddonor.repository;

import com.blooddonor.entity.DonorResponse;
import com.blooddonor.entity.RequestStatus;
import com.blooddonor.entity.ResponseStatus;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
