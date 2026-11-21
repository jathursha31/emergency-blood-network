package com.blooddonor.service;

import com.blooddonor.dto.HospitalDtos.CreateHospital;
import com.blooddonor.dto.HospitalDtos.HospitalView;
import com.blooddonor.entity.Hospital;
import com.blooddonor.exception.NotFoundException;
import com.blooddonor.repository.HospitalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class HospitalService {

    private final HospitalRepository hospitalRepository;

    public HospitalService(HospitalRepository hospitalRepository) {
        this.hospitalRepository = hospitalRepository;
    }

    public HospitalView create(CreateHospital req) {
        Hospital h = new Hospital();
        h.setName(req.name().trim());
        h.setPhone(req.phone());
        h.setAddress(req.address());
        h.setCity(req.city());
        h.setLatitude(req.latitude());
        h.setLongitude(req.longitude());
        return toView(hospitalRepository.save(h));
    }

    @Transactional(readOnly = true)
    public HospitalView get(Long id) {
        return toView(find(id));
    }

    @Transactional(readOnly = true)
    public List<HospitalView> list() {
        return hospitalRepository.findAll().stream().map(this::toView).toList();
    }

    Hospital find(Long id) {
        return hospitalRepository.findById(id).orElseThrow(() -> new NotFoundException("Hospital", id));
    }

    private HospitalView toView(Hospital h) {
        return new HospitalView(h.getId(), h.getName(), h.getPhone(), h.getAddress(), h.getCity(),
                h.getLatitude(), h.getLongitude());
    }
}
