package com.blooddonor.service;

import com.blooddonor.entity.ResponseStatus;
import com.blooddonor.repository.DonorResponseRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/** Counts completed donations per donor across every source (emergency requests, camps). */
@Component
@Transactional(readOnly = true)
public class DonationCounter {

    private final DonorResponseRepository responses;

    public DonationCounter(DonorResponseRepository responses) {
        this.responses = responses;
    }

    public long countFor(Long donorId) {
        return responses.countByDonorIdAndStatus(donorId, ResponseStatus.DONATED);
    }

    /** donorId -> number of donations, only donors with at least one. */
    public Map<Long, Long> countAll() {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : responses.countByDonorGrouped(ResponseStatus.DONATED)) {
            counts.merge((Long) row[0], (Long) row[1], Long::sum);
        }
        return counts;
    }
}
