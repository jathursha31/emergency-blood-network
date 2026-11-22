package com.blooddonor.entity;

/** Donor reward levels, earned by number of completed donations. */
public enum Badge {
    NONE(0, "New donor", ""),
    FIRST_DROP(1, "First Drop", "🩸"),
    BRONZE(3, "Bronze Donor", "🥉"),
    LIFE_SAVER(5, "Life Saver", "🥈"),
    HERO(10, "Hero", "🥇"),
    LEGEND(25, "Legend", "💎");

    private final int minDonations;
    private final String title;
    private final String icon;

    Badge(int minDonations, String title, String icon) {
        this.minDonations = minDonations;
        this.title = title;
        this.icon = icon;
    }

    public int minDonations() { return minDonations; }
    public String title() { return title; }
    public String icon() { return icon; }

    public static Badge forCount(long donations) {
        Badge result = NONE;
        for (Badge b : values()) {
            if (donations >= b.minDonations) result = b;
        }
        return result;
    }

    /** The next level up, or null at the top. */
    public Badge next() {
        return ordinal() + 1 < values().length ? values()[ordinal() + 1] : null;
    }
}
