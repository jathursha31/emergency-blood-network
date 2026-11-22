package com.blooddonor.service;

import com.blooddonor.dto.RewardDtos.BadgeView;
import com.blooddonor.dto.RewardDtos.LeaderboardEntry;
import com.blooddonor.dto.RewardDtos.MyRewards;
import com.blooddonor.entity.Badge;
import com.blooddonor.entity.Donor;
import com.blooddonor.repository.DonorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Badges and leaderboard. A "donation" is any completed donation the system knows about
 * (emergency requests and camps), counted by {@link DonationCounter}.
 */
@Service
@Transactional(readOnly = true)
public class RewardService {

    private static final int LIVES_PER_DONATION = 3;

    private final DonationCounter counter;
    private final DonorRepository donorRepository;

    public RewardService(DonationCounter counter, DonorRepository donorRepository) {
        this.counter = counter;
        this.donorRepository = donorRepository;
    }

    public MyRewards forDonor(Long donorId) {
        long donations = counter.countFor(donorId);
        Badge badge = Badge.forCount(donations);
        Badge next = badge.next();
        int rank = leaderboard(Integer.MAX_VALUE).stream()
                .filter(e -> e.donorId().equals(donorId))
                .map(RankedDonor::rank)
                .findFirst().orElse(0);
        return new MyRewards(donations, donations * LIVES_PER_DONATION,
                BadgeView.of(badge), BadgeView.of(next),
                next == null ? 0 : next.minDonations() - donations,
                rank,
                Arrays.stream(Badge.values()).filter(b -> b != Badge.NONE).map(BadgeView::of).toList());
    }

    public List<LeaderboardEntry> publicLeaderboard(int limit) {
        return leaderboard(limit).stream()
                .map(r -> new LeaderboardEntry(r.rank(), shortName(r.donor().getFullName()),
                        r.donor().getBloodGroup().label(), r.donor().getCity(), r.donations(),
                        BadgeView.of(Badge.forCount(r.donations()))))
                .toList();
    }

    /** Donors with at least one donation, most first; ties share a rank (1, 2, 2, 4...). */
    private List<RankedDonor> leaderboard(int limit) {
        Map<Long, Long> counts = counter.countAll();
        if (counts.isEmpty()) return List.of();
        Map<Long, Donor> donors = donorRepository.findAllById(counts.keySet()).stream()
                .collect(java.util.stream.Collectors.toMap(Donor::getId, d -> d));

        List<Map.Entry<Long, Long>> sorted = counts.entrySet().stream()
                .filter(e -> donors.containsKey(e.getKey()))
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed()
                        .thenComparing(e -> donors.get(e.getKey()).getFullName()))
                .toList();

        int[] ranks = new int[sorted.size()];
        IntStream.range(0, sorted.size()).forEach(i -> ranks[i] =
                i > 0 && sorted.get(i).getValue().equals(sorted.get(i - 1).getValue()) ? ranks[i - 1] : i + 1);

        return IntStream.range(0, Math.min(limit, sorted.size()))
                .mapToObj(i -> new RankedDonor(ranks[i], sorted.get(i).getKey(),
                        donors.get(sorted.get(i).getKey()), sorted.get(i).getValue()))
                .sorted(Comparator.comparingInt(RankedDonor::rank))
                .toList();
    }

    /** "Arun Kumar" -> "Arun K." */
    static String shortName(String fullName) {
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 1) return parts[0];
        return parts[0] + " " + parts[parts.length - 1].charAt(0) + ".";
    }

    private record RankedDonor(int rank, Long donorId, Donor donor, long donations) {
    }
}
