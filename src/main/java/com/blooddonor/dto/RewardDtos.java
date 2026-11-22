package com.blooddonor.dto;

import com.blooddonor.entity.Badge;

import java.util.List;

public final class RewardDtos {

    private RewardDtos() {
    }

    public record BadgeView(Badge badge, String title, String icon, int minDonations) {
        public static BadgeView of(Badge b) {
            return b == null ? null : new BadgeView(b, b.title(), b.icon(), b.minDonations());
        }
    }

    public record MyRewards(
            long donations,
            /* one donation can help up to 3 patients (red cells, plasma, platelets) */
            long livesHelped,
            BadgeView badge,
            BadgeView nextBadge,
            long donationsToNext,
            int leaderboardRank,
            List<BadgeView> allBadges
    ) {
    }

    /** Public leaderboard row: short name only (e.g. "Arun K."), never phone or exact location. */
    public record LeaderboardEntry(
            int rank,
            String displayName,
            String bloodGroup,
            String city,
            long donations,
            BadgeView badge
    ) {
    }
}
