package com.blooddonor.service;

import com.blooddonor.dto.DonorDtos.*;
import com.blooddonor.entity.BloodGroup;
import com.blooddonor.entity.Donor;
import com.blooddonor.entity.DonorResponse;
import com.blooddonor.entity.ResponseStatus;
import com.blooddonor.exception.BusinessRuleException;
import com.blooddonor.exception.NotFoundException;
import com.blooddonor.repository.DonorRepository;
import com.blooddonor.repository.DonorResponseRepository;
import com.blooddonor.util.GeoUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@Transactional
public class DonorService {

    private final DonorRepository donorRepository;
    private final DonorResponseRepository responseRepository;
    private final EligibilityService eligibilityService;
    private final DonorMatchingService matchingService;

    public DonorService(DonorRepository donorRepository,
                        DonorResponseRepository responseRepository,
                        EligibilityService eligibilityService,
                        DonorMatchingService matchingService) {
        this.donorRepository = donorRepository;
        this.responseRepository = responseRepository;
        this.eligibilityService = eligibilityService;
        this.matchingService = matchingService;
    }

    public DonorView register(RegisterDonor req) {
        if (donorRepository.existsByPhone(req.phone())) {
            throw new BusinessRuleException("A donor with phone " + req.phone() + " is already registered");
        }
        Donor donor = new Donor();
        donor.setFullName(req.fullName().trim());
        donor.setPhone(req.phone());
        donor.setEmail(req.email());
        donor.setDateOfBirth(req.dateOfBirth());
        donor.setBloodGroup(req.bloodGroup());
        donor.setLatitude(req.latitude());
        donor.setLongitude(req.longitude());
        donor.setCity(req.city());
        donor.setLastDonationDate(req.lastDonationDate());
        return toView(donorRepository.save(donor));
    }

    @Transactional(readOnly = true)
    public DonorView get(Long id) {
        return toView(find(id));
    }

    public DonorView updateAvailability(Long id, boolean available) {
        Donor donor = find(id);
        donor.setAvailable(available);
        return toView(donor);
    }

    @Transactional(readOnly = true)
    public Eligibility eligibility(Long id) {
        return eligibilityService.check(find(id));
    }

    @Transactional(readOnly = true)
    public List<DonationRecord> donationHistory(Long id) {
        find(id);
        return responseRepository.findByDonorIdAndStatusOrderByDonatedAtDesc(id, ResponseStatus.DONATED)
                .stream()
                .map(this::toDonationRecord)
                .toList();
    }

    /** Public "who can donate near me" search. Phones are masked. */
    @Transactional(readOnly = true)
    public List<NearbyDonor> searchNearby(BloodGroup recipientGroup, double lat, double lng, double radiusKm) {
        if (radiusKm <= 0 || radiusKm > 100) {
            throw new BusinessRuleException("radiusKm must be between 0 and 100");
        }
        return matchingService.findNearbyEligible(recipientGroup, lat, lng, radiusKm, 50, Set.of())
                .stream()
                .map(m -> new NearbyDonor(
                        m.donor().getId(),
                        m.donor().getFullName(),
                        m.donor().getBloodGroup(),
                        m.donor().getCity(),
                        GeoUtils.round1(m.distanceKm()),
                        maskPhone(m.donor().getPhone())))
                .toList();
    }

    Donor find(Long id) {
        return donorRepository.findById(id).orElseThrow(() -> new NotFoundException("Donor", id));
    }

    static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return "****";
        return phone.substring(0, 2) + "*".repeat(phone.length() - 4) + phone.substring(phone.length() - 2);
    }

    private DonationRecord toDonationRecord(DonorResponse r) {
        return new DonationRecord(
                r.getRequest().getId(),
                r.getRequest().getHospital().getName(),
                r.getRequest().getBloodGroup().label(),
                r.getDonatedAt());
    }

    private DonorView toView(Donor d) {
        return new DonorView(d.getId(), d.getFullName(), d.getPhone(), d.getEmail(),
                d.getBloodGroup(), d.getBloodGroup().label(), d.getCity(),
                d.getLatitude(), d.getLongitude(), d.getLastDonationDate(),
                d.isAvailable(), d.getRegisteredAt());
    }
}
