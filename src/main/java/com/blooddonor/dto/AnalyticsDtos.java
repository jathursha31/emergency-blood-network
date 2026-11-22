package com.blooddonor.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class AnalyticsDtos {

    private AnalyticsDtos() {
    }

    public record Analytics(
            Kpis kpis,
            List<GroupStat> byBloodGroup,
            List<DayStat> last14Days,
            List<HospitalStat> byHospital,
            Map<String, Long> requestStatus
    ) {
    }

    public record Kpis(
            long totalRequests,
            long activeRequests,
            long fulfilledRequests,
            /* fulfilled / (fulfilled + cancelled + expired), 0..100 */
            double fulfillmentRatePct,
            long unitsDonated,
            long donorsRegistered,
            long donorsEligibleNow,
            /* minutes from request creation to the first donor accepting; null if no data */
            Double avgFirstResponseMinutes,
            /* minutes from creation to fulfilled; null if no data */
            Double avgTimeToFulfilMinutes,
            long upcomingCamps,
            long unitsInBloodBanks
    ) {
    }

    /** Demand (requests) vs supply (donors) for one blood group. */
    public record GroupStat(String bloodGroup, long requests, long unitsRequested, long donors, long eligibleDonors,
                            long unitsInStock) {
    }

    public record DayStat(LocalDate date, long created, long fulfilled) {
    }

    public record HospitalStat(Long hospitalId, String hospitalName, long requests, long fulfilled,
                               Double avgFirstResponseMinutes) {
    }
}
