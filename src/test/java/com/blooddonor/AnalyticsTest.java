package com.blooddonor;

import com.blooddonor.dto.AnalyticsDtos.Analytics;
import com.blooddonor.dto.AnalyticsDtos.GroupStat;
import com.blooddonor.entity.BloodGroup;
import com.blooddonor.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Other test classes may leave data behind, so compare before/after instead of absolute numbers. */
@SpringBootTest
@Transactional
class AnalyticsTest {

    @Autowired HospitalService hospitalService;
    @Autowired DonorService donorService;
    @Autowired BloodRequestService requestService;
    @Autowired AnalyticsService analytics;

    private static GroupStat group(Analytics a, String label) {
        return a.byBloodGroup().stream().filter(g -> g.bloodGroup().equals(label)).findFirst().orElseThrow();
    }

    @Test
    void countsRequestsDonationsAndDemandByGroup() {
        Fixtures fx = new Fixtures(hospitalService, donorService, requestService);
        Long hospitalId = fx.hospital("Stats Hospital");
        Analytics before = analytics.compute();

        var donor = fx.donor("Stats Donor", BloodGroup.AB_NEG);
        fx.donate(hospitalId, donor);                       // 1 request, fulfilled
        fx.request(hospitalId, BloodGroup.AB_NEG, 3);       // 1 open request, 3 units

        Analytics after = analytics.compute();
        assertThat(after.kpis().totalRequests()).isEqualTo(before.kpis().totalRequests() + 2);
        assertThat(after.kpis().fulfilledRequests()).isEqualTo(before.kpis().fulfilledRequests() + 1);
        assertThat(after.kpis().activeRequests()).isEqualTo(before.kpis().activeRequests() + 1);
        assertThat(after.kpis().unitsDonated()).isEqualTo(before.kpis().unitsDonated() + 1);
        assertThat(after.kpis().donorsRegistered()).isEqualTo(before.kpis().donorsRegistered() + 1);
        assertThat(after.kpis().avgFirstResponseMinutes()).isNotNull();

        assertThat(group(after, "AB-").requests()).isEqualTo(group(before, "AB-").requests() + 2);
        assertThat(group(after, "AB-").unitsRequested()).isEqualTo(group(before, "AB-").unitsRequested() + 4);
        assertThat(group(after, "AB-").donors()).isEqualTo(group(before, "AB-").donors() + 1);

        assertThat(after.last14Days()).hasSize(14);
        assertThat(after.last14Days().getLast().date()).isEqualTo(LocalDate.now());
        assertThat(after.last14Days().getLast().created()).isGreaterThanOrEqualTo(2);
        assertThat(after.byHospital()).anySatisfy(h -> {
            assertThat(h.hospitalName()).isEqualTo("Stats Hospital");
            assertThat(h.requests()).isEqualTo(2);
            assertThat(h.fulfilled()).isEqualTo(1);
        });
    }
}
