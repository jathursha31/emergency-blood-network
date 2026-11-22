package com.blooddonor.live;

import java.util.Map;
import java.util.Set;

/**
 * Something changed that open browsers should know about.
 * Delivered to: admins always, hospitals listed in hospitalIds, donors listed in donorIds.
 *
 * @param type        e.g. "request.created", "request.updated", "inventory.updated", "camp.updated"
 * @param hospitalIds hospitals that should hear about it
 * @param donorIds    donors that should hear about it
 * @param data        small JSON payload (ids and a short message), never personal data
 */
public record LiveEvent(String type, Set<Long> hospitalIds, Set<Long> donorIds, Map<String, Object> data) {

    public static LiveEvent toHospital(String type, Long hospitalId, Map<String, Object> data) {
        return new LiveEvent(type, Set.of(hospitalId), Set.of(), data);
    }
}
