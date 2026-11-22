package com.blooddonor.service;

import com.blooddonor.dto.CampDtos.*;
import com.blooddonor.dto.DonorDtos.Eligibility;
import com.blooddonor.entity.*;
import com.blooddonor.exception.BusinessRuleException;
import com.blooddonor.exception.ForbiddenException;
import com.blooddonor.exception.NotFoundException;
import com.blooddonor.live.LiveEvent;
import com.blooddonor.repository.CampBookingRepository;
import com.blooddonor.repository.DonationCampRepository;
import com.blooddonor.repository.DonorRepository;
import com.blooddonor.security.CurrentUser;
import com.blooddonor.service.events.DonationEvents;
import com.blooddonor.util.GeoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Blood donation camps: hospital creates a camp, nearby donors are invited, donors book a slot
 * (QR pass), and the hospital checks them in on the day, which records the donation and adds a unit to stock.
 */
@Service
@Transactional
public class CampService {

    private static final Logger log = LoggerFactory.getLogger(CampService.class);
    private static final double INVITE_RADIUS_KM = 15;
    private static final int MAX_INVITES = 50;
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM");

    private final DonationCampRepository campRepository;
    private final CampBookingRepository bookingRepository;
    private final DonorRepository donorRepository;
    private final HospitalService hospitalService;
    private final DonorMatchingService matchingService;
    private final EligibilityService eligibilityService;
    private final NotificationService notifier;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public CampService(DonationCampRepository campRepository, CampBookingRepository bookingRepository,
                       DonorRepository donorRepository, HospitalService hospitalService,
                       DonorMatchingService matchingService, EligibilityService eligibilityService,
                       NotificationService notifier, ApplicationEventPublisher events, Clock clock) {
        this.campRepository = campRepository;
        this.bookingRepository = bookingRepository;
        this.donorRepository = donorRepository;
        this.hospitalService = hospitalService;
        this.matchingService = matchingService;
        this.eligibilityService = eligibilityService;
        this.notifier = notifier;
        this.events = events;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- hospital side

    public CampView create(CreateCamp req) {
        if (req.hospitalId() == null) throw new IllegalArgumentException("hospitalId is required");
        if (!req.endTime().isAfter(req.startTime())) {
            throw new BusinessRuleException("End time must be after start time");
        }
        DonationCamp camp = new DonationCamp();
        camp.setHospital(hospitalService.find(req.hospitalId()));
        camp.setName(req.name().trim());
        camp.setAddress(req.address());
        camp.setCity(req.city());
        camp.setLatitude(req.latitude());
        camp.setLongitude(req.longitude());
        camp.setCampDate(req.campDate());
        camp.setStartTime(req.startTime());
        camp.setEndTime(req.endTime());
        camp.setSlotMinutes(req.slotMinutes());
        camp.setCapacityPerSlot(req.capacityPerSlot());
        if (camp.slotTimes().isEmpty()) {
            throw new BusinessRuleException("The camp is shorter than one " + req.slotMinutes() + "-minute slot");
        }
        camp.setCreatedAt(now());
        campRepository.save(camp);

        int invited = inviteNearbyDonors(camp);
        log.info("Camp #{} '{}' on {} created, {} donors invited", camp.getId(), camp.getName(), camp.getCampDate(), invited);
        return toView(camp, null, null);
    }

    public List<CampView> hosted(Long hospitalId) {
        var camps = hospitalId == null ? campRepository.findAllByOrderByCampDateDesc()
                : campRepository.findByHospitalIdOrderByCampDateDesc(hospitalId);
        return camps.stream().map(c -> toView(c, null, null)).toList();
    }

    public List<BookingRow> bookings(Long campId, CurrentUser user) {
        DonationCamp camp = find(campId);
        requireOwner(camp, user);
        return bookingRepository.findByCampIdOrderBySlotTimeAscBookedAtAsc(campId).stream().map(this::toRow).toList();
    }

    public CampView cancel(Long campId, CurrentUser user) {
        DonationCamp camp = lock(campId);
        requireOwner(camp, user);
        if (camp.getStatus() == DonationCamp.Status.CANCELLED) throw new BusinessRuleException("Camp is already cancelled");
        camp.setStatus(DonationCamp.Status.CANCELLED);
        for (CampBooking b : bookingRepository.findByCampIdOrderBySlotTimeAscBookedAtAsc(campId)) {
            if (b.getStatus() == CampBooking.Status.BOOKED) {
                b.setStatus(CampBooking.Status.CANCELLED);
                notifier.send(b.getDonor().getPhone(), "Sorry, the blood donation camp '" + camp.getName()
                        + "' on " + camp.getCampDate().format(DAY) + " has been cancelled.");
            }
        }
        campChanged(camp, null);
        return toView(camp, null, null);
    }

    /** Hospital checks a donor in with the code from their pass. Records the donation. */
    public BookingRow checkIn(String rawCode, CurrentUser user) {
        String code = rawCode.trim().toUpperCase();
        CampBooking b = bookingRepository.findByCode(code)
                .orElseThrow(() -> new NotFoundException("Booking with code", code));
        DonationCamp camp = b.getCamp();
        requireOwner(camp, user);
        if (b.getStatus() == CampBooking.Status.ATTENDED) throw new BusinessRuleException("Already checked in at " + b.getCheckedInAt());
        if (b.getStatus() == CampBooking.Status.CANCELLED) throw new BusinessRuleException("This booking was cancelled");
        if (!camp.getCampDate().equals(today())) {
            throw new BusinessRuleException("Check-in is only possible on the camp day (" + camp.getCampDate() + ")");
        }
        Donor donor = b.getDonor();
        Eligibility e = eligibilityService.check(donor);
        if (!e.eligible()) throw new BusinessRuleException("Donor is not eligible today: " + String.join("; ", e.reasons()));

        b.setStatus(CampBooking.Status.ATTENDED);
        b.setCheckedInAt(now());
        donor.setLastDonationDate(today());
        events.publishEvent(new DonationEvents.UnitsCollected(camp.getHospital().getId(), donor.getBloodGroup(), 1,
                "camp #" + camp.getId()));
        notifier.send(donor.getPhone(), "Thank you for donating at " + camp.getName()
                + "! You can donate again in 90 days.");
        campChanged(camp, "✅ " + donor.getFullName() + " checked in at " + camp.getName());
        return toRow(b);
    }

    // ---------------------------------------------------------------- public / donor side

    public List<CampView> upcoming(Double lat, Double lng) {
        return campRepository.findByStatusAndCampDateGreaterThanEqualOrderByCampDateAscStartTimeAsc(
                        DonationCamp.Status.ACTIVE, today()).stream()
                .map(c -> toView(c, lat, lng))
                .toList();
    }

    public CampView get(Long campId) {
        return toView(find(campId), null, null);
    }

    public MyBooking book(Long donorId, BookSlot req) {
        DonationCamp camp = lock(req.campId());
        if (camp.getStatus() != DonationCamp.Status.ACTIVE) throw new BusinessRuleException("This camp was cancelled");
        if (camp.getCampDate().isBefore(today())) throw new BusinessRuleException("This camp is over");
        if (!camp.slotTimes().contains(req.slotTime())) {
            throw new BusinessRuleException("There is no slot at " + req.slotTime());
        }
        LocalTime slotEnd = req.slotTime().plusMinutes(camp.getSlotMinutes());
        if (camp.getCampDate().equals(today()) && !LocalTime.now(clock).isBefore(slotEnd)
                && slotEnd.isAfter(req.slotTime())) {
            throw new BusinessRuleException("That slot is already over");
        }
        if (bookingRepository.existsByCampIdAndDonorIdAndStatusNot(camp.getId(), donorId, CampBooking.Status.CANCELLED)) {
            throw new BusinessRuleException("You already have a booking at this camp");
        }
        Donor donor = donorRepository.findById(donorId).orElseThrow(() -> new NotFoundException("Donor", donorId));
        Eligibility e = eligibilityService.checkOn(donor, camp.getCampDate());
        if (!e.eligible()) {
            throw new BusinessRuleException("You can't donate on " + camp.getCampDate() + ": " + String.join("; ", e.reasons()));
        }
        long taken = bookingRepository.countByCampIdAndSlotTimeAndStatusNot(camp.getId(), req.slotTime(), CampBooking.Status.CANCELLED);
        if (taken >= camp.getCapacityPerSlot()) throw new BusinessRuleException("The " + req.slotTime() + " slot is full");

        CampBooking b = new CampBooking();
        b.setCamp(camp);
        b.setDonor(donor);
        b.setSlotTime(req.slotTime());
        b.setCode(newCode());
        b.setBookedAt(now());
        bookingRepository.save(b);
        notifier.send(donor.getPhone(), "Booked: " + camp.getName() + " on " + camp.getCampDate().format(DAY)
                + " at " + req.slotTime() + ". Your check-in code is " + b.getCode() + ".");
        campChanged(camp, "🎟️ New booking for " + camp.getName() + " at " + req.slotTime());
        return toMine(b);
    }

    public List<MyBooking> myBookings(Long donorId) {
        return bookingRepository.findByDonorIdOrderByBookedAtDesc(donorId).stream().map(this::toMine).toList();
    }

    public MyBooking cancelBooking(Long donorId, Long bookingId) {
        CampBooking b = bookingRepository.findById(bookingId).orElseThrow(() -> new NotFoundException("Booking", bookingId));
        if (!b.getDonor().getId().equals(donorId)) throw new ForbiddenException("Not your booking");
        if (b.getStatus() != CampBooking.Status.BOOKED) throw new BusinessRuleException("Booking is " + b.getStatus());
        b.setStatus(CampBooking.Status.CANCELLED);
        campChanged(b.getCamp(), null);
        return toMine(b);
    }

    // ---------------------------------------------------------------- helpers

    /** Every eligible donor nearby is welcome at a camp, whatever their group (AB+ can receive from all groups). */
    private int inviteNearbyDonors(DonationCamp camp) {
        var donors = matchingService.findNearbyEligible(BloodGroup.AB_POS, camp.getLatitude(), camp.getLongitude(),
                INVITE_RADIUS_KM, MAX_INVITES, Set.of());
        for (var m : donors) {
            notifier.send(m.donor().getPhone(), "🩸 Blood donation camp: " + camp.getName() + ", "
                    + camp.getCampDate().format(DAY) + " " + camp.getStartTime() + "-" + camp.getEndTime()
                    + " (" + GeoUtils.round1(m.distanceKm()) + " km away). Book a slot in the app.");
        }
        Set<Long> donorIds = donors.stream().map(m -> m.donor().getId()).collect(Collectors.toSet());
        if (!donorIds.isEmpty()) {
            events.publishEvent(new LiveEvent("camp.created", Set.of(), donorIds,
                    Map.of("campId", camp.getId(), "message", "⛺ New donation camp near you: " + camp.getName())));
        }
        return donors.size();
    }

    private void campChanged(DonationCamp camp, String message) {
        Map<String, Object> data = new java.util.HashMap<>(Map.of("campId", camp.getId()));
        if (message != null) data.put("message", message);
        events.publishEvent(LiveEvent.toHospital("camp.updated", camp.getHospital().getId(), data));
    }

    private String newCode() {
        String code;
        do {
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 8; i++) sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
            code = sb.toString();
        } while (bookingRepository.existsByCode(code));
        return code;
    }

    private void requireOwner(DonationCamp camp, CurrentUser user) {
        if (user.isAdmin()) return;
        if (user.role() == Role.HOSPITAL && camp.getHospital().getId().equals(user.hospitalId())) return;
        throw new ForbiddenException("This camp belongs to another hospital");
    }

    private DonationCamp find(Long id) {
        return campRepository.findById(id).orElseThrow(() -> new NotFoundException("Camp", id));
    }

    private DonationCamp lock(Long id) {
        return campRepository.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Camp", id));
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private CampView toView(DonationCamp c, Double lat, Double lng) {
        List<CampBooking> bookings = bookingRepository.findByCampIdOrderBySlotTimeAscBookedAtAsc(c.getId());
        Map<LocalTime, Long> perSlot = bookings.stream()
                .filter(b -> b.getStatus() != CampBooking.Status.CANCELLED)
                .collect(Collectors.groupingBy(CampBooking::getSlotTime, Collectors.counting()));
        List<SlotView> slots = c.slotTimes().stream().map(t -> {
            int booked = perSlot.getOrDefault(t, 0L).intValue();
            return new SlotView(t, booked, c.getCapacityPerSlot(), Math.max(0, c.getCapacityPerSlot() - booked));
        }).toList();
        int booked = perSlot.values().stream().mapToInt(Long::intValue).sum();
        int attended = (int) bookings.stream().filter(b -> b.getStatus() == CampBooking.Status.ATTENDED).count();
        Double distance = lat != null && lng != null
                ? GeoUtils.round1(GeoUtils.distanceKm(lat, lng, c.getLatitude(), c.getLongitude())) : null;
        return new CampView(c.getId(), c.getHospital().getId(), c.getHospital().getName(), c.getName(), c.getAddress(),
                c.getCity(), c.getLatitude(), c.getLongitude(), c.getCampDate(), c.getStartTime(), c.getEndTime(),
                c.getSlotMinutes(), c.getCapacityPerSlot(), c.getStatus(), slots.size() * c.getCapacityPerSlot(),
                booked, attended, distance, slots);
    }

    private MyBooking toMine(CampBooking b) {
        DonationCamp c = b.getCamp();
        return new MyBooking(b.getId(), b.getCode(), b.getStatus(), b.getSlotTime(), b.getBookedAt(), b.getCheckedInAt(),
                c.getId(), c.getName(), c.getHospital().getName(), c.getAddress(), c.getCampDate(), c.getStatus(),
                c.getLatitude(), c.getLongitude());
    }

    private BookingRow toRow(CampBooking b) {
        Donor d = b.getDonor();
        return new BookingRow(b.getId(), b.getCode(), b.getStatus(), b.getSlotTime(), d.getId(), d.getFullName(),
                d.getBloodGroup().label(), d.getPhone(), b.getCheckedInAt());
    }
}
