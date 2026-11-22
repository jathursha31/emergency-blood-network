package com.blooddonor.service;

import com.blooddonor.dto.AnalyticsDtos.*;
import com.blooddonor.entity.*;
import com.blooddonor.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Admin dashboard numbers. Computed in memory from the current data, which is fine at this scale;
 * for millions of rows these would become SQL aggregate queries or a reporting table.
 */
@Service
@Transactional(readOnly = true)
public class AnalyticsService {

    private final BloodRequestRepository requests;
    private final DonorRepository donors;
    private final BloodStockRepository stock;
    private final DonationCampRepository camps;
    private final DonationCounter donationCounter;
    private final EligibilityService eligibility;
    private final Clock clock;

    public AnalyticsService(BloodRequestRepository requests, DonorRepository donors, BloodStockRepository stock,
                            DonationCampRepository camps, DonationCounter donationCounter,
                            EligibilityService eligibility, Clock clock) {
        this.requests = requests;
        this.donors = donors;
        this.stock = stock;
        this.camps = camps;
        this.donationCounter = donationCounter;
        this.eligibility = eligibility;
        this.clock = clock;
    }

    public Analytics compute() {
        List<BloodRequest> all = requests.findAll();
        List<Donor> allDonors = donors.findAll();
        Set<Long> eligibleIds = allDonors.stream().filter(d -> eligibility.check(d).eligible())
                .map(Donor::getId).collect(Collectors.toSet());
        List<BloodStock> allStock = stock.findAll();

        Map<String, Long> status = new LinkedHashMap<>();
        for (RequestStatus s : RequestStatus.values()) {
            status.put(s.name(), all.stream().filter(r -> r.getStatus() == s).count());
        }
        long fulfilled = status.get("FULFILLED");
        long closedOther = status.get("CANCELLED") + status.get("EXPIRED");

        Kpis kpis = new Kpis(
                all.size(),
                status.get("OPEN") + status.get("MATCHED"),
                fulfilled,
                fulfilled + closedOther == 0 ? 0 : round1(100.0 * fulfilled / (fulfilled + closedOther)),
                donationCounter.countAll().values().stream().mapToLong(Long::longValue).sum(),
                allDonors.size(),
                eligibleIds.size(),
                avg(all.stream().map(this::firstResponseMinutes)),
                avg(all.stream().filter(r -> r.getStatus() == RequestStatus.FULFILLED && r.getClosedAt() != null)
                        .map(r -> (double) Duration.between(r.getCreatedAt(), r.getClosedAt()).toMinutes())),
                camps.findByStatusAndCampDateGreaterThanEqualOrderByCampDateAscStartTimeAsc(
                        DonationCamp.Status.ACTIVE, today()).size(),
                allStock.stream().mapToLong(BloodStock::getUnits).sum());

        List<GroupStat> byGroup = Arrays.stream(BloodGroup.values()).map(g -> new GroupStat(
                g.label(),
                all.stream().filter(r -> r.getBloodGroup() == g).count(),
                all.stream().filter(r -> r.getBloodGroup() == g).mapToLong(BloodRequest::getUnitsNeeded).sum(),
                allDonors.stream().filter(d -> d.getBloodGroup() == g).count(),
                allDonors.stream().filter(d -> d.getBloodGroup() == g && eligibleIds.contains(d.getId())).count(),
                allStock.stream().filter(s -> s.getBloodGroup() == g).mapToLong(BloodStock::getUnits).sum()
        )).toList();

        LocalDate today = today();
        List<DayStat> days = new ArrayList<>();
        for (int i = 13; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            days.add(new DayStat(day,
                    all.stream().filter(r -> r.getCreatedAt().toLocalDate().equals(day)).count(),
                    all.stream().filter(r -> r.getStatus() == RequestStatus.FULFILLED && r.getClosedAt() != null
                            && r.getClosedAt().toLocalDate().equals(day)).count()));
        }

        List<HospitalStat> byHospital = all.stream()
                .collect(Collectors.groupingBy(r -> r.getHospital().getId(), LinkedHashMap::new, Collectors.toList()))
                .values().stream()
                .map(list -> new HospitalStat(list.getFirst().getHospital().getId(), list.getFirst().getHospital().getName(),
                        list.size(), list.stream().filter(r -> r.getStatus() == RequestStatus.FULFILLED).count(),
                        avg(list.stream().map(this::firstResponseMinutes))))
                .sorted(Comparator.comparingLong(HospitalStat::requests).reversed())
                .toList();

        return new Analytics(kpis, byGroup, days, byHospital, status);
    }

    /** Minutes until the first donor accepted, or null if nobody did. */
    private Double firstResponseMinutes(BloodRequest r) {
        return r.getResponses().stream()
                .filter(x -> x.getRespondedAt() != null
                        && (x.getStatus() == ResponseStatus.ACCEPTED || x.getStatus() == ResponseStatus.DONATED))
                .map(x -> (double) Duration.between(r.getCreatedAt(), x.getRespondedAt()).toMinutes())
                .min(Double::compare).orElse(null);
    }

    private static Double avg(java.util.stream.Stream<Double> values) {
        OptionalDouble a = values.filter(Objects::nonNull).mapToDouble(Double::doubleValue).average();
        return a.isPresent() ? round1(a.getAsDouble()) : null;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
