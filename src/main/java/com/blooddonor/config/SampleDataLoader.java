package com.blooddonor.config;

import com.blooddonor.entity.BloodGroup;
import com.blooddonor.entity.Donor;
import com.blooddonor.entity.Hospital;
import com.blooddonor.repository.DonorRepository;
import com.blooddonor.entity.Role;
import com.blooddonor.repository.HospitalRepository;
import com.blooddonor.repository.UserAccountRepository;
import com.blooddonor.security.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Loads fictional Chennai-area hospitals and donors on first start so the API can be tried at once.
 * Disable with app.sample-data=false.
 */
@Component
@Order(2)
@ConditionalOnProperty(name = "app.sample-data", havingValue = "true")
public class SampleDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleDataLoader.class);

    private final HospitalRepository hospitalRepository;
    private final DonorRepository donorRepository;
    private final UserAccountRepository accounts;
    private final AuthService authService;

    public SampleDataLoader(HospitalRepository hospitalRepository, DonorRepository donorRepository,
                            UserAccountRepository accounts, AuthService authService) {
        this.hospitalRepository = hospitalRepository;
        this.donorRepository = donorRepository;
        this.accounts = accounts;
        this.authService = authService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (hospitalRepository.count() == 0 && donorRepository.count() == 0) {
            loadHospitalsAndDonors();
        }
        createMissingLogins();
    }

    /**
     * Demo logins, username = phone number:
     * hospitals use password "hospital123", donors use "donor123".
     */
    private void createMissingLogins() {
        int created = 0;
        for (Hospital h : hospitalRepository.findAll()) {
            if (!accounts.existsByHospitalId(h.getId()) && !accounts.existsByUsername(h.getPhone())) {
                authService.createAccount(h.getPhone(), "hospital123", Role.HOSPITAL, null, h);
                created++;
            }
        }
        for (Donor d : donorRepository.findAll()) {
            if (!accounts.existsByDonorId(d.getId()) && !accounts.existsByUsername(d.getPhone())) {
                authService.createAccount(d.getPhone(), "donor123", Role.DONOR, d, null);
                created++;
            }
        }
        if (created > 0) log.info("Created {} demo logins (hospital123 / donor123)", created);
    }

    private void loadHospitalsAndDonors() {
        hospital("City Care Hospital", "04420001111", "T. Nagar", 13.0418, 80.2341);
        hospital("Sunrise Multispeciality", "04420002222", "Velachery", 12.9815, 80.2180);
        hospital("Lakeview Hospital", "04420003333", "Anna Nagar", 13.0850, 80.2101);

        LocalDate today = LocalDate.now();
        donor("Arun Kumar", "9840000001", BloodGroup.B_POS, "T. Nagar", 13.0400, 80.2330, 1995, null);
        donor("Priya Raman", "9840000002", BloodGroup.O_NEG, "Mylapore", 13.0368, 80.2676, 1990, today.minusDays(200));
        donor("Karthik S", "9840000003", BloodGroup.B_POS, "Adyar", 13.0012, 80.2565, 1988, today.minusDays(30));
        donor("Divya M", "9840000004", BloodGroup.A_POS, "Velachery", 12.9790, 80.2200, 1998, null);
        donor("Suresh Babu", "9840000005", BloodGroup.O_POS, "Guindy", 13.0067, 80.2206, 1985, today.minusDays(120));
        donor("Lakshmi N", "9840000006", BloodGroup.AB_POS, "Anna Nagar", 13.0860, 80.2090, 1993, null);
        donor("Vignesh R", "9840000007", BloodGroup.B_NEG, "Porur", 13.0382, 80.1565, 1996, today.minusDays(95));
        donor("Meena K", "9840000008", BloodGroup.O_POS, "Tambaram", 12.9249, 80.1000, 1991, null);
        donor("Rahul D", "9840000009", BloodGroup.B_POS, "Chengalpattu", 12.6921, 79.9707, 1994, null);
        donor("Sathya P", "9840000010", BloodGroup.A_NEG, "Nungambakkam", 13.0569, 80.2425, 1989, today.minusDays(60));

        log.info("Sample data loaded: {} hospitals, {} donors", hospitalRepository.count(), donorRepository.count());
    }

    private void hospital(String name, String phone, String city, double lat, double lng) {
        Hospital h = new Hospital();
        h.setName(name);
        h.setPhone(phone);
        h.setCity(city);
        h.setAddress(city + ", Chennai");
        h.setLatitude(lat);
        h.setLongitude(lng);
        hospitalRepository.save(h);
    }

    private void donor(String name, String phone, BloodGroup group, String city,
                       double lat, double lng, int birthYear, LocalDate lastDonation) {
        Donor d = new Donor();
        d.setFullName(name);
        d.setPhone(phone);
        d.setBloodGroup(group);
        d.setCity(city);
        d.setLatitude(lat);
        d.setLongitude(lng);
        d.setDateOfBirth(LocalDate.of(birthYear, 6, 15));
        d.setLastDonationDate(lastDonation);
        donorRepository.save(d);
    }
}
