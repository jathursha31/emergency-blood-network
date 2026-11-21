package com.blooddonor;

import com.blooddonor.dto.DonorDtos.DonorView;
import com.blooddonor.dto.DonorDtos.RegisterDonor;
import com.blooddonor.dto.HospitalDtos.CreateHospital;
import com.blooddonor.dto.RequestDtos.BloodRequestView;
import com.blooddonor.dto.RequestDtos.CreateBloodRequest;
import com.blooddonor.dto.RequestDtos.ResponseView;
import com.blooddonor.entity.*;
import com.blooddonor.exception.BusinessRuleException;
import com.blooddonor.exception.NotFoundException;
import com.blooddonor.repository.BloodRequestRepository;
import com.blooddonor.service.BloodRequestService;
import com.blooddonor.service.DonorService;
import com.blooddonor.service.HospitalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class BloodRequestFlowTest {

    // Hospital in T. Nagar, Chennai
    static final double H_LAT = 13.0418, H_LNG = 80.2341;

    @Autowired HospitalService hospitalService;
    @Autowired DonorService donorService;
    @Autowired BloodRequestService requestService;
    @Autowired BloodRequestRepository requestRepository;

    Long hospitalId;
    DonorView nearBPos, nearONeg, recentDonor, nearAPos, farBPos, tooYoung, unavailable;

    @BeforeEach
    void setUp() {
        hospitalId = hospitalService.create(new CreateHospital(
                "Test Hospital", "04412345678", "T. Nagar", "Chennai", H_LAT, H_LNG,
                "flow_test_staff", "password123")).id();

        LocalDate today = LocalDate.now();
        nearBPos = donor("Near B+", "9000000001", BloodGroup.B_POS, 13.0400, 80.2330, 1995, null);
        nearONeg = donor("Near O-", "9000000002", BloodGroup.O_NEG, 13.0500, 80.2400, 1990, today.minusDays(100));
        recentDonor = donor("Recent B+", "9000000003", BloodGroup.B_POS, 13.0410, 80.2350, 1992, today.minusDays(30));
        nearAPos = donor("Near A+", "9000000004", BloodGroup.A_POS, 13.0420, 80.2340, 1993, null);
        farBPos = donor("Far B+", "9000000005", BloodGroup.B_POS, 12.9000, 80.2341, 1994, null); // ~16 km
        tooYoung = donor("Young B+", "9000000006", BloodGroup.B_POS, 13.0419, 80.2342, today.getYear() - 16, null);
        unavailable = donor("Busy B+", "9000000007", BloodGroup.B_POS, 13.0417, 80.2343, 1990, null);
        donorService.updateAvailability(unavailable.id(), false);
    }

    @Test
    void notifiesOnlyNearbyEligibleCompatibleDonors_exactGroupFirst() {
        BloodRequestView req = createRequest(1, Urgency.HIGH);

        assertThat(req.status()).isEqualTo(RequestStatus.OPEN);
        assertThat(req.searchRadiusKm()).isEqualTo(10);
        assertThat(req.donors()).extracting(ResponseView::donorId)
                .containsExactly(nearBPos.id(), nearONeg.id());
        // phones are masked until the donor accepts
        assertThat(req.donors().getFirst().phone()).isEqualTo("90******01");
    }

    @Test
    void criticalRequestStartsWithDoubleRadius() {
        BloodRequestView req = createRequest(1, Urgency.CRITICAL);
        assertThat(req.searchRadiusKm()).isEqualTo(20);
        assertThat(req.donors()).extracting(ResponseView::donorId).contains(farBPos.id());
    }

    @Test
    void fullLifecycle_accept_backOut_replace_donate_fulfil() {
        Long id = createRequest(1, Urgency.HIGH).id();

        // a donor who was never notified cannot accept
        assertThatThrownBy(() -> requestService.accept(id, nearAPos.id())).isInstanceOf(NotFoundException.class);

        BloodRequestView afterAccept = requestService.accept(id, nearBPos.id());
        assertThat(afterAccept.status()).isEqualTo(RequestStatus.MATCHED);
        assertThat(afterAccept.donors().getFirst().phone()).isEqualTo("9000000001"); // revealed now

        // no more accepts once matched
        assertThatThrownBy(() -> requestService.accept(id, nearONeg.id())).isInstanceOf(BusinessRuleException.class);

        // accepted donor backs out -> request reopens
        assertThat(requestService.decline(id, nearBPos.id()).status()).isEqualTo(RequestStatus.OPEN);

        requestService.accept(id, nearONeg.id());
        BloodRequestView done = requestService.markDonated(id, nearONeg.id());
        assertThat(done.status()).isEqualTo(RequestStatus.FULFILLED);
        assertThat(done.closedAt()).isNotNull();

        // donor's last donation date is updated, so they are no longer eligible
        assertThat(donorService.get(nearONeg.id()).lastDonationDate()).isEqualTo(LocalDate.now());
        assertThat(donorService.eligibility(nearONeg.id()).eligible()).isFalse();
        assertThat(donorService.eligibility(nearONeg.id()).daysRemaining()).isEqualTo(90);
        assertThat(donorService.donationHistory(nearONeg.id())).hasSize(1);

        assertThatThrownBy(() -> requestService.cancel(id)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void donorCanHoldOnlyOneActiveAcceptance() {
        Long first = createRequest(1, Urgency.HIGH).id();
        Long second = createRequest(1, Urgency.HIGH).id();

        requestService.accept(first, nearBPos.id());

        // same donor can't also commit to the second request
        assertThatThrownBy(() -> requestService.accept(second, nearBPos.id()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already accepted request #" + first);

        // after backing out of the first, the second can be accepted
        requestService.decline(first, nearBPos.id());
        assertThat(requestService.accept(second, nearBPos.id()).status()).isEqualTo(RequestStatus.MATCHED);
    }

    @Test
    void cancelledRequestReleasesTheDonor() {
        Long first = createRequest(1, Urgency.HIGH).id();
        Long second = createRequest(1, Urgency.HIGH).id();

        requestService.accept(first, nearBPos.id());
        requestService.cancel(first);

        assertThat(requestService.accept(second, nearBPos.id()).status()).isEqualTo(RequestStatus.MATCHED);
    }

    @Test
    void cannotMarkDonatedWithoutAccepting() {
        Long id = createRequest(2, Urgency.NORMAL).id();
        assertThatThrownBy(() -> requestService.markDonated(id, nearBPos.id()))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void staleRequestWidensRadiusAndNotifiesNewDonors() {
        Long id = createRequest(3, Urgency.HIGH).id();
        BloodRequest entity = requestRepository.findById(id).orElseThrow();
        entity.setLastSearchAt(LocalDateTime.now().minusHours(1));

        assertThat(requestService.expandStaleSearches()).isEqualTo(1);

        BloodRequestView after = requestService.get(id);
        assertThat(after.searchRadiusKm()).isEqualTo(20);
        assertThat(after.donors()).extracting(ResponseView::donorId)
                .containsExactlyInAnyOrder(nearBPos.id(), nearONeg.id(), farBPos.id());
    }

    @Test
    void overdueRequestExpires() {
        Long id = createRequest(1, Urgency.NORMAL).id();
        requestRepository.findById(id).orElseThrow().setNeededBy(LocalDateTime.now().minusMinutes(1));

        assertThat(requestService.expireOverdue()).isEqualTo(1);
        assertThat(requestService.get(id).status()).isEqualTo(RequestStatus.EXPIRED);
    }

    @Test
    void publicSearchReturnsEligibleDonorsWithMaskedPhones() {
        var results = donorService.searchNearby(BloodGroup.B_POS, H_LAT, H_LNG, 10);
        assertThat(results).extracting(r -> r.donorId()).containsExactly(nearBPos.id(), nearONeg.id());
        assertThat(results.getFirst().maskedPhone()).doesNotContain("000000");
    }

    @Test
    void duplicatePhoneRejected() {
        assertThatThrownBy(() -> donor("Copy", "9000000001", BloodGroup.A_POS, 13, 80, 1990, null))
                .isInstanceOf(BusinessRuleException.class);
    }

    private BloodRequestView createRequest(int units, Urgency urgency) {
        return requestService.create(new CreateBloodRequest(
                hospitalId, "Patient X", BloodGroup.B_POS, units, urgency, LocalDateTime.now().plusHours(6)));
    }

    private DonorView donor(String name, String phone, BloodGroup group, double lat, double lng,
                            int birthYear, LocalDate lastDonation) {
        return donorService.register(new RegisterDonor(name, phone, null, LocalDate.of(birthYear, 1, 1),
                group, lat, lng, "Chennai", lastDonation));
    }
}
