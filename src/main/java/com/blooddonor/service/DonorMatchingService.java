package com.blooddonor.service;

import com.blooddonor.entity.BloodGroup;
import com.blooddonor.entity.Donor;
import com.blooddonor.repository.DonorRepository;
import com.blooddonor.util.GeoUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Finds eligible, blood-compatible donors near a point.
 * Step 1 (SQL): bounding box + eligibility filters.
 * Step 2 (Java): exact Haversine distance, drop the box corners, sort nearest first.
 */
@Service
public class DonorMatchingService {

    private final DonorRepository donorRepository;
    private final EligibilityService eligibility;

    public DonorMatchingService(DonorRepository donorRepository, EligibilityService eligibility) {
        this.donorRepository = donorRepository;
        this.eligibility = eligibility;
    }

    @Transactional(readOnly = true)
    public List<Match> findNearbyEligible(BloodGroup recipientGroup, double lat, double lng,
                                          double radiusKm, int limit, Set<Long> excludeDonorIds) {
        GeoUtils.BoundingBox box = GeoUtils.boundingBox(lat, lng, radiusKm);
        List<Donor> candidates = donorRepository.findEligibleInBox(
                recipientGroup.compatibleDonors(),
                eligibility.lastDonationCutoff(),
                eligibility.minDateOfBirth(),
                eligibility.maxDateOfBirth(),
                box.minLat(), box.maxLat(), box.minLng(), box.maxLng());

        return candidates.stream()
                .filter(d -> !excludeDonorIds.contains(d.getId()))
                .map(d -> new Match(d, GeoUtils.distanceKm(lat, lng, d.getLatitude(), d.getLongitude())))
                .filter(m -> m.distanceKm() <= radiusKm)
                // exact group match first (saves universal O- blood), then nearest
                .sorted(Comparator.comparing((Match m) -> m.donor().getBloodGroup() != recipientGroup)
                        .thenComparingDouble(Match::distanceKm))
                .limit(limit)
                .toList();
    }

    public record Match(Donor donor, double distanceKm) {
    }
}
