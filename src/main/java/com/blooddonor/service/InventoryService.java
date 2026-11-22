package com.blooddonor.service;

import com.blooddonor.dto.InventoryDtos.*;
import com.blooddonor.entity.*;
import com.blooddonor.exception.BusinessRuleException;
import com.blooddonor.repository.BloodRequestRepository;
import com.blooddonor.repository.BloodStockRepository;
import com.blooddonor.repository.HospitalRepository;
import com.blooddonor.service.events.DonationEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Hospital blood bank. Every hospital has one row per blood group (created on first use).
 * When a group falls below its minimum and auto-request is on, a restock request is raised
 * to nearby donors; units donated for that request flow back into stock.
 */
@Service
@Transactional
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);
    private static final EnumSet<RequestStatus> ACTIVE = EnumSet.of(RequestStatus.OPEN, RequestStatus.MATCHED);

    private final BloodStockRepository stockRepository;
    private final HospitalRepository hospitalRepository;
    private final BloodRequestRepository requestRepository;
    private final BloodRequestService requestService;
    private final HospitalService hospitalService;
    private final Clock clock;

    public InventoryService(BloodStockRepository stockRepository, HospitalRepository hospitalRepository,
                            BloodRequestRepository requestRepository, BloodRequestService requestService,
                            HospitalService hospitalService, Clock clock) {
        this.stockRepository = stockRepository;
        this.hospitalRepository = hospitalRepository;
        this.requestRepository = requestRepository;
        this.requestService = requestService;
        this.hospitalService = hospitalService;
        this.clock = clock;
    }

    public List<StockView> list(Long hospitalId) {
        hospitalService.find(hospitalId);
        Map<BloodGroup, BloodStock> byGroup = new EnumMap<>(BloodGroup.class);
        stockRepository.findByHospitalId(hospitalId).forEach(s -> byGroup.put(s.getBloodGroup(), s));
        return Arrays.stream(BloodGroup.values())
                .map(g -> byGroup.containsKey(g) ? toView(byGroup.get(g)) : emptyView(g))
                .toList();
    }

    public List<HospitalStock> overview() {
        return hospitalRepository.findAll().stream().map(h -> {
            List<StockView> stock = list(h.getId());
            return new HospitalStock(h.getId(), h.getName(), h.getCity(),
                    stock.stream().mapToInt(StockView::units).sum(),
                    (int) stock.stream().filter(s -> s.level() != StockLevel.OK && s.minUnits() > 0).count(),
                    stock);
        }).toList();
    }

    public StockView update(Long hospitalId, BloodGroup group, UpdateStock req) {
        BloodStock s = lockOrCreate(hospitalId, group);
        if (req.units() != null) s.setUnits(req.units());
        if (req.minUnits() != null) s.setMinUnits(req.minUnits());
        if (req.autoRequest() != null) s.setAutoRequest(req.autoRequest());
        touch(s);
        raiseRestockIfLow(s);
        return toView(s);
    }

    /** Units issued to patients (transfusions). */
    public StockView use(Long hospitalId, BloodGroup group, int units) {
        BloodStock s = lockOrCreate(hospitalId, group);
        if (units > s.getUnits()) {
            throw new BusinessRuleException("Only " + s.getUnits() + " unit(s) of " + group.label() + " in stock");
        }
        s.setUnits(s.getUnits() - units);
        touch(s);
        raiseRestockIfLow(s);
        return toView(s);
    }

    /** Units received (camp donations, restock donations, transfers from another bank). */
    public StockView add(Long hospitalId, BloodGroup group, int units) {
        BloodStock s = lockOrCreate(hospitalId, group);
        s.setUnits(s.getUnits() + units);
        touch(s);
        return toView(s);
    }

    @EventListener
    public void onUnitsCollected(DonationEvents.UnitsCollected e) {
        add(e.hospitalId(), e.bloodGroup(), e.units());
        log.info("Blood bank of hospital {}: +{} {} ({})", e.hospitalId(), e.units(), e.bloodGroup().label(), e.source());
    }

    // ---------------------------------------------------------------- helpers

    private void raiseRestockIfLow(BloodStock s) {
        if (!s.isLow() || !s.isAutoRequest()) return;
        Long hospitalId = s.getHospital().getId();
        if (activeRestock(hospitalId, s.getBloodGroup()).isPresent()) return;

        int needed = Math.min(10, s.getMinUnits() - s.getUnits());
        Urgency urgency = s.getUnits() == 0 ? Urgency.HIGH : Urgency.NORMAL;
        var request = requestService.createRestock(hospitalId, s.getBloodGroup(), needed, urgency);
        log.info("Stock of {} at hospital {} is {} (min {}): restock request #{} for {} unit(s)",
                s.getBloodGroup().label(), hospitalId, s.getUnits(), s.getMinUnits(), request.id(), needed);
    }

    private Optional<BloodRequest> activeRestock(Long hospitalId, BloodGroup group) {
        return requestRepository.findFirstByHospitalIdAndBloodGroupAndRestockTrueAndStatusIn(hospitalId, group, ACTIVE);
    }

    private BloodStock lockOrCreate(Long hospitalId, BloodGroup group) {
        return stockRepository.findForUpdate(hospitalId, group).orElseGet(() -> {
            BloodStock s = new BloodStock();
            s.setHospital(hospitalService.find(hospitalId));
            s.setBloodGroup(group);
            return stockRepository.saveAndFlush(s);
        });
    }

    private void touch(BloodStock s) {
        s.setUpdatedAt(LocalDateTime.now(clock));
    }

    private StockView toView(BloodStock s) {
        StockLevel level = s.getUnits() == 0 ? StockLevel.EMPTY : s.isLow() ? StockLevel.LOW : StockLevel.OK;
        Long restockId = activeRestock(s.getHospital().getId(), s.getBloodGroup()).map(BloodRequest::getId).orElse(null);
        return new StockView(s.getBloodGroup(), s.getBloodGroup().label(), s.getUnits(), s.getMinUnits(),
                s.isAutoRequest(), level, restockId, s.getUpdatedAt());
    }

    private StockView emptyView(BloodGroup g) {
        return new StockView(g, g.label(), 0, 0, true, StockLevel.EMPTY, null, null);
    }
}
