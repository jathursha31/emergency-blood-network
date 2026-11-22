package com.blooddonor;

import com.blooddonor.dto.DonorDtos.DonorView;
import com.blooddonor.dto.InventoryDtos.StockLevel;
import com.blooddonor.dto.InventoryDtos.StockView;
import com.blooddonor.dto.InventoryDtos.UpdateStock;
import com.blooddonor.entity.BloodGroup;
import com.blooddonor.entity.RequestStatus;
import com.blooddonor.exception.BusinessRuleException;
import com.blooddonor.service.BloodRequestService;
import com.blooddonor.service.DonorService;
import com.blooddonor.service.HospitalService;
import com.blooddonor.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class InventoryTest {

    @Autowired HospitalService hospitalService;
    @Autowired DonorService donorService;
    @Autowired BloodRequestService requestService;
    @Autowired InventoryService inventory;

    Fixtures fx;
    Long hospitalId;

    @BeforeEach
    void setUp() {
        fx = new Fixtures(hospitalService, donorService, requestService);
        hospitalId = fx.hospital("Bank Hospital");
    }

    private StockView stock(BloodGroup g) {
        return inventory.list(hospitalId).stream().filter(s -> s.bloodGroup() == g).findFirst().orElseThrow();
    }

    @Test
    void listsAllEightGroupsEvenBeforeAnyStock() {
        assertThat(inventory.list(hospitalId)).hasSize(8).allMatch(s -> s.units() == 0 && s.level() == StockLevel.EMPTY);
    }

    @Test
    void cannotUseMoreThanInStock() {
        inventory.update(hospitalId, BloodGroup.A_POS, new UpdateStock(2, 0, false));
        assertThatThrownBy(() -> inventory.use(hospitalId, BloodGroup.A_POS, 3))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Only 2");
    }

    @Test
    void fallingBelowMinimumRaisesOneRestockRequestAndDonationsRefillStock() {
        DonorView donor = fx.donor("Stock Donor", BloodGroup.B_POS);
        inventory.update(hospitalId, BloodGroup.B_POS, new UpdateStock(6, 4, true));

        StockView afterUse = inventory.use(hospitalId, BloodGroup.B_POS, 3); // 3 left, min 4
        assertThat(afterUse.level()).isEqualTo(StockLevel.LOW);
        assertThat(afterUse.restockRequestId()).isNotNull();

        var restock = requestService.get(afterUse.restockRequestId());
        assertThat(restock.restock()).isTrue();
        assertThat(restock.unitsNeeded()).isEqualTo(1);
        assertThat(restock.donors()).extracting(d -> d.donorId()).contains(donor.id());

        // using more does not raise a second request while one is open
        StockView again = inventory.use(hospitalId, BloodGroup.B_POS, 1);
        assertThat(again.restockRequestId()).isEqualTo(afterUse.restockRequestId());
        assertThat(requestService.list(RequestStatus.OPEN, hospitalId)).hasSize(1);

        // the donor gives blood for the restock request -> it goes into the blood bank
        requestService.accept(restock.id(), donor.id());
        requestService.markDonated(restock.id(), donor.id());
        assertThat(stock(BloodGroup.B_POS).units()).isEqualTo(3);
        assertThat(requestService.get(restock.id()).status()).isEqualTo(RequestStatus.FULFILLED);
    }

    @Test
    void noRequestWhenAutoRequestIsOff() {
        inventory.update(hospitalId, BloodGroup.O_NEG, new UpdateStock(1, 5, false));
        assertThat(stock(BloodGroup.O_NEG).restockRequestId()).isNull();
        assertThat(requestService.list(null, hospitalId)).isEmpty();
    }

    @Test
    void ordinaryRequestDonationsDoNotChangeStock() {
        DonorView donor = fx.donor("Patient Donor", BloodGroup.A_NEG);
        fx.donate(hospitalId, donor);
        assertThat(stock(BloodGroup.A_NEG).units()).isZero();
    }
}
