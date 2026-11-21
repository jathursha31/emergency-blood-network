package com.blooddonor;

import com.blooddonor.entity.BloodGroup;
import com.blooddonor.util.GeoUtils;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static com.blooddonor.entity.BloodGroup.*;
import static org.assertj.core.api.Assertions.assertThat;

class BloodGroupAndGeoTest {

    @Test
    void oNegativeIsUniversalDonor() {
        for (BloodGroup recipient : BloodGroup.values()) {
            assertThat(O_NEG.canDonateTo(recipient)).isTrue();
        }
    }

    @Test
    void abPositiveIsUniversalRecipient() {
        assertThat(AB_POS.compatibleDonors()).containsExactlyInAnyOrder(BloodGroup.values());
    }

    @Test
    void compatibilityRules() {
        assertThat(A_POS.compatibleDonors()).isEqualTo(EnumSet.of(A_POS, A_NEG, O_POS, O_NEG));
        assertThat(B_NEG.compatibleDonors()).isEqualTo(EnumSet.of(B_NEG, O_NEG));
        assertThat(O_POS.compatibleDonors()).isEqualTo(EnumSet.of(O_POS, O_NEG));
        assertThat(O_NEG.compatibleDonors()).isEqualTo(EnumSet.of(O_NEG));
        assertThat(B_POS.canDonateTo(A_POS)).isFalse();
        assertThat(A_POS.canDonateTo(A_NEG)).isFalse();
    }

    @Test
    void haversineDistanceInChennai() {
        // T. Nagar -> Velachery is roughly 7 km
        double km = GeoUtils.distanceKm(13.0418, 80.2341, 12.9815, 80.2180);
        assertThat(km).isBetween(6.5, 7.5);
    }

    @Test
    void boundingBoxContainsCircle() {
        GeoUtils.BoundingBox box = GeoUtils.boundingBox(13.0, 80.0, 10);
        assertThat(GeoUtils.distanceKm(13.0, 80.0, box.maxLat(), 80.0)).isCloseTo(10, org.assertj.core.data.Offset.offset(0.01));
        assertThat(GeoUtils.distanceKm(13.0, 80.0, 13.0, box.maxLng())).isGreaterThanOrEqualTo(9.99);
    }
}
