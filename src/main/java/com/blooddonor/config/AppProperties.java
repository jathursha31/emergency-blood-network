package com.blooddonor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Business rules, overridable in application.properties with the "app." prefix,
 * e.g. app.min-days-between-donations=90
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @DefaultValue("90") int minDaysBetweenDonations,
        @DefaultValue("18") int minDonorAge,
        @DefaultValue("65") int maxDonorAge,
        /* starting search radius; CRITICAL requests start at double this */
        @DefaultValue("10") double initialRadiusKm,
        /* how much the radius grows each time nobody responds */
        @DefaultValue("10") double radiusStepKm,
        @DefaultValue("50") double maxRadiusKm,
        /* wait this long for responses before widening the search */
        @DefaultValue("30") int expandAfterMinutes,
        /* nearest N donors notified per search round */
        @DefaultValue("25") int maxDonorsPerSearch
) {
}
