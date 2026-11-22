package com.blooddonor;

import com.blooddonor.dto.CampDtos.*;
import com.blooddonor.dto.DonorDtos.DonorView;
import com.blooddonor.entity.BloodGroup;
import com.blooddonor.entity.CampBooking;
import com.blooddonor.entity.Role;
import com.blooddonor.exception.BusinessRuleException;
import com.blooddonor.exception.ForbiddenException;
import com.blooddonor.security.CurrentUser;
import com.blooddonor.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class CampTest {

    @Autowired HospitalService hospitalService;
    @Autowired DonorService donorService;
    @Autowired BloodRequestService requestService;
    @Autowired CampService campService;
    @Autowired InventoryService inventory;
    @Autowired RewardService rewards;

    Fixtures fx;
    Long hospitalId;
    CurrentUser staff;

    @BeforeEach
    void setUp() {
        fx = new Fixtures(hospitalService, donorService, requestService);
        hospitalId = fx.hospital("Camp Hospital");
        staff = new CurrentUser("staff", Role.HOSPITAL, null, hospitalId);
    }

    private CampView camp(LocalDate date, LocalTime start, LocalTime end, int slotMinutes, int capacity) {
        return campService.create(new CreateCamp(hospitalId, "Anna Nagar Camp", "Community hall", "Chennai",
                Fixtures.H_LAT, Fixtures.H_LNG, date, start, end, slotMinutes, capacity));
    }

    /** One slot 00:00-23:59 today, so check-in tests pass whatever time they run. */
    private CampView wholeDayCampToday() {
        return camp(LocalDate.now(), LocalTime.MIDNIGHT, LocalTime.of(23, 59), 1439, 3);
    }

    @Test
    void slotsAreGeneratedFromTheTimeWindow() {
        CampView c = camp(LocalDate.now().plusDays(3), LocalTime.of(9, 0), LocalTime.of(11, 0), 30, 2);
        assertThat(c.slots()).extracting(SlotView::time)
                .containsExactly(LocalTime.of(9, 0), LocalTime.of(9, 30), LocalTime.of(10, 0), LocalTime.of(10, 30));
        assertThat(c.totalCapacity()).isEqualTo(8);
    }

    @Test
    void slotsNearMidnightDoNotWrapAround() {
        CampView c = camp(LocalDate.now().plusDays(1), LocalTime.of(23, 0), LocalTime.of(23, 59), 30, 1);
        assertThat(c.slots()).extracting(SlotView::time).containsExactly(LocalTime.of(23, 0));
    }

    @Test
    void bookingRulesCapacityDuplicateAndEligibility() {
        CampView c = camp(LocalDate.now().plusDays(3), LocalTime.of(9, 0), LocalTime.of(10, 0), 30, 1);
        DonorView a = fx.donor("Camp A", BloodGroup.O_POS);
        DonorView b = fx.donor("Camp B", BloodGroup.A_NEG);
        DonorView recent = fx.donor("Camp Recent", BloodGroup.B_POS, 13.04, 80.23, LocalDate.now().minusDays(30));

        MyBooking booking = campService.book(a.id(), new BookSlot(c.id(), LocalTime.of(9, 0)));
        assertThat(booking.code()).hasSize(8);

        assertThatThrownBy(() -> campService.book(a.id(), new BookSlot(c.id(), LocalTime.of(9, 30))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("already have a booking");
        assertThatThrownBy(() -> campService.book(b.id(), new BookSlot(c.id(), LocalTime.of(9, 0))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("full");
        assertThatThrownBy(() -> campService.book(b.id(), new BookSlot(c.id(), LocalTime.of(9, 15))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("no slot");
        assertThatThrownBy(() -> campService.book(recent.id(), new BookSlot(c.id(), LocalTime.of(9, 30))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("must wait 90 days");

        // cancelling frees the place
        campService.cancelBooking(a.id(), booking.id());
        campService.book(b.id(), new BookSlot(c.id(), LocalTime.of(9, 0)));
        assertThat(campService.get(c.id()).slots().getFirst().available()).isZero();
    }

    @Test
    void donorWhoBecomesEligibleBeforeTheCampCanBook() {
        CampView c = camp(LocalDate.now().plusDays(20), LocalTime.of(9, 0), LocalTime.of(10, 0), 30, 5);
        DonorView soon = fx.donor("Almost Ready", BloodGroup.O_NEG, 13.04, 80.23, LocalDate.now().minusDays(80));
        assertThat(campService.book(soon.id(), new BookSlot(c.id(), LocalTime.of(9, 0))).status())
                .isEqualTo(CampBooking.Status.BOOKED);
    }

    @Test
    void checkInRecordsDonationStockAndBadge() {
        CampView c = wholeDayCampToday();
        DonorView d = fx.donor("Camp Giver", BloodGroup.AB_NEG);
        MyBooking booking = campService.book(d.id(), new BookSlot(c.id(), LocalTime.MIDNIGHT));

        BookingRow row = campService.checkIn(booking.code().toLowerCase(), staff);
        assertThat(row.status()).isEqualTo(CampBooking.Status.ATTENDED);

        assertThat(donorService.get(d.id()).lastDonationDate()).isEqualTo(LocalDate.now());
        assertThat(inventory.list(hospitalId)).filteredOn(s -> s.bloodGroup() == BloodGroup.AB_NEG)
                .singleElement().extracting(s -> s.units()).isEqualTo(1);
        assertThat(rewards.forDonor(d.id()).donations()).isEqualTo(1);
        assertThat(donorService.donationHistory(d.id())).singleElement()
                .satisfies(r -> assertThat(r.hospitalName()).contains("Anna Nagar Camp"));

        assertThatThrownBy(() -> campService.checkIn(booking.code(), staff))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Already checked in");
    }

    @Test
    void otherHospitalCannotCheckInOrCancel() {
        CampView c = wholeDayCampToday();
        DonorView d = fx.donor("Other Check", BloodGroup.A_POS);
        MyBooking booking = campService.book(d.id(), new BookSlot(c.id(), LocalTime.MIDNIGHT));
        CurrentUser other = new CurrentUser("x", Role.HOSPITAL, null, fx.hospital("Other Hospital"));

        assertThatThrownBy(() -> campService.checkIn(booking.code(), other)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> campService.cancel(c.id(), other)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void checkInOnlyOnCampDay() {
        CampView c = camp(LocalDate.now().plusDays(2), LocalTime.of(9, 0), LocalTime.of(10, 0), 30, 3);
        DonorView d = fx.donor("Early Bird", BloodGroup.B_NEG);
        MyBooking booking = campService.book(d.id(), new BookSlot(c.id(), LocalTime.of(9, 0)));
        assertThatThrownBy(() -> campService.checkIn(booking.code(), staff))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("only possible on the camp day");
    }

    @Test
    void cancellingACampCancelsItsBookings() {
        CampView c = camp(LocalDate.now().plusDays(1), LocalTime.of(9, 0), LocalTime.of(10, 0), 30, 3);
        DonorView d = fx.donor("Cancelled Guest", BloodGroup.O_POS);
        campService.book(d.id(), new BookSlot(c.id(), LocalTime.of(9, 0)));

        campService.cancel(c.id(), staff);
        assertThat(campService.myBookings(d.id())).singleElement()
                .satisfies(b -> assertThat(b.status()).isEqualTo(CampBooking.Status.CANCELLED));
        assertThat(campService.upcoming(null, null)).noneMatch(x -> x.id().equals(c.id()));
    }
}
