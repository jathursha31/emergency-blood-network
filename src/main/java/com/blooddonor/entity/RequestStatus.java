package com.blooddonor.entity;

/**
 * OPEN      - looking for donors
 * MATCHED   - enough donors accepted, waiting for them to donate
 * FULFILLED - required units donated
 * CANCELLED - hospital cancelled it
 * EXPIRED   - neededBy time passed while still OPEN/MATCHED
 */
public enum RequestStatus {
    OPEN, MATCHED, FULFILLED, CANCELLED, EXPIRED;

    public boolean isClosed() {
        return this == FULFILLED || this == CANCELLED || this == EXPIRED;
    }
}
