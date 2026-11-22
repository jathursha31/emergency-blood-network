package com.blooddonor;

import com.blooddonor.dto.DonorDtos.DonorView;
import com.blooddonor.dto.DonorDtos.RegisterDonor;
import com.blooddonor.dto.HospitalDtos.CreateHospital;
import com.blooddonor.dto.RequestDtos.BloodRequestView;
import com.blooddonor.dto.RequestDtos.CreateBloodRequest;
import com.blooddonor.entity.BloodGroup;
import com.blooddonor.entity.Urgency;
import com.blooddonor.service.BloodRequestService;
import com.blooddonor.service.DonorService;
import com.blooddonor.service.HospitalService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/** Small helpers for creating test hospitals, donors and requests (T. Nagar, Chennai area). */
public class Fixtures {

    public static final double H_LAT = 13.0418, H_LNG = 80.2341;
    private static final AtomicInteger SEQ = new AtomicInteger();

    private final HospitalService hospitals;
    private final DonorService donors;
    private final BloodRequestService requests;

    public Fixtures(HospitalService hospitals, DonorService donors, BloodRequestService requests) {
        this.hospitals = hospitals;
        this.donors = donors;
        this.requests = requests;
    }

    public Long hospital(String name) {
        int n = SEQ.incrementAndGet();
        return hospitals.create(new CreateHospital(name, "0441234" + String.format("%04d", n), "T. Nagar", "Chennai",
                H_LAT, H_LNG, "staff_" + n + "_" + System.nanoTime(), "password123")).id();
    }

    /** Eligible donor near the hospital. */
    public DonorView donor(String name, BloodGroup group) {
        return donor(name, group, 13.0400, 80.2330, null);
    }

    public DonorView donor(String name, BloodGroup group, double lat, double lng, LocalDate lastDonation) {
        String phone = "8" + String.format("%09d", SEQ.incrementAndGet() + 100_000);
        return donors.register(new RegisterDonor(name, phone, null, LocalDate.of(1995, 1, 1),
                group, lat, lng, "Chennai", lastDonation));
    }

    public BloodRequestView request(Long hospitalId, BloodGroup group, int units) {
        return requests.create(new CreateBloodRequest(hospitalId, "Patient", group, units, Urgency.HIGH,
                LocalDateTime.now().plusHours(6)));
    }

    /** Full accept + donated cycle for one donor on a fresh request. */
    public void donate(Long hospitalId, DonorView donor) {
        BloodRequestView r = request(hospitalId, donor.bloodGroup(), 1);
        requests.accept(r.id(), donor.id());
        requests.markDonated(r.id(), donor.id());
    }
}
