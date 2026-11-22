package com.blooddonor.service.events;

import com.blooddonor.entity.BloodGroup;

/**
 * Domain events published inside the business transaction. Listeners run in the same transaction
 * (inventory) or after commit (live updates). Using events keeps services from depending on each other in a cycle.
 */
public final class DonationEvents {

    private DonationEvents() {
    }

    /** Blood was collected and should go into a hospital's blood bank (restock request or donation camp). */
    public record UnitsCollected(Long hospitalId, BloodGroup bloodGroup, int units, String source) {
    }
}
