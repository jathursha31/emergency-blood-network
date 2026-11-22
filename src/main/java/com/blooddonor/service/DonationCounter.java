package com.blooddonor.service;

import com.blooddonor.entity.CampBooking;
import com.blooddonor.entity.ResponseStatus;
import com.blooddonor.repository.CampBookingRepository;
import com.blooddonor.repository.DonorResponseRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Counts completed donations per donor across every source: emergency requests and camp check-ins. */
@Component
@Transactional(readOnly = true)
public class DonationCounter {

    private final DonorResponseRepository responses;
    private final CampBookingRepository campBookings;

    public DonationCounter(DonorResponseRepository responses, CampBookingRepository campBookings) {
        this.responses = responses;
        this.campBookings = campBookings;
    }

    public long countFor(Long donorId) {
        return responses.countByDonorIdAndStatus(donorId, ResponseStatus.DONATED)
                + campBookings.countByDonorIdAndStatus(donorId, CampBooking.Status.ATTENDED);
    }

    /** donorId -> number of donations, only donors with at least one. */
    public Map<Long, Long> countAll() {
        Map<Long, Long> counts = new HashMap<>();
        add(counts, responses.countByDonorGrouped(ResponseStatus.DONATED));
        add(counts, campBookings.countByDonorGrouped(CampBooking.Status.ATTENDED));
        return counts;
    }

    private static void add(Map<Long, Long> counts, List<Object[]> rows) {
        for (Object[] row : rows) counts.merge((Long) row[0], (Long) row[1], Long::sum);
    }
}
