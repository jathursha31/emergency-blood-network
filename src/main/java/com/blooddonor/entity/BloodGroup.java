package com.blooddonor.entity;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * Blood groups with red-cell compatibility rules.
 * A donor can give to a recipient when every antigen the donor has (A, B, Rh)
 * is also present in the recipient.
 */
public enum BloodGroup {
    O_NEG("O-", false, false, false),
    O_POS("O+", false, false, true),
    A_NEG("A-", true, false, false),
    A_POS("A+", true, false, true),
    B_NEG("B-", false, true, false),
    B_POS("B+", false, true, true),
    AB_NEG("AB-", true, true, false),
    AB_POS("AB+", true, true, true);

    private final String label;
    private final boolean hasA;
    private final boolean hasB;
    private final boolean rhPositive;

    BloodGroup(String label, boolean hasA, boolean hasB, boolean rhPositive) {
        this.label = label;
        this.hasA = hasA;
        this.hasB = hasB;
        this.rhPositive = rhPositive;
    }

    public String label() {
        return label;
    }

    public boolean canDonateTo(BloodGroup recipient) {
        return (!hasA || recipient.hasA)
                && (!hasB || recipient.hasB)
                && (!rhPositive || recipient.rhPositive);
    }

    /** All donor groups whose blood this recipient can safely receive. */
    public Set<BloodGroup> compatibleDonors() {
        Set<BloodGroup> result = EnumSet.noneOf(BloodGroup.class);
        Arrays.stream(values()).filter(d -> d.canDonateTo(this)).forEach(result::add);
        return result;
    }
}
