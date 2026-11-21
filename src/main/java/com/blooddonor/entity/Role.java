package com.blooddonor.entity;

public enum Role {
    /** Manages hospitals, sees everything. */
    ADMIN,
    /** Raises requests, confirms donations, cancels its own requests. */
    HOSPITAL,
    /** Sees requests sent to them, accepts or declines, manages own profile. */
    DONOR
}
