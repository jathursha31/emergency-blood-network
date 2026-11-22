package com.blooddonor.service;

import com.blooddonor.config.AppProperties;
import com.blooddonor.dto.RequestDtos;
import com.blooddonor.dto.RequestDtos.BloodRequestView;
import com.blooddonor.dto.RequestDtos.CreateBloodRequest;
import com.blooddonor.dto.RequestDtos.ResponseView;
import com.blooddonor.dto.DonorDtos.Eligibility;
import com.blooddonor.entity.*;
import com.blooddonor.exception.BusinessRuleException;
import com.blooddonor.exception.NotFoundException;
import com.blooddonor.live.LiveEvent;
import com.blooddonor.push.PushMessage;
import com.blooddonor.repository.BloodRequestRepository;
import com.blooddonor.repository.DonorRepository;
import com.blooddonor.repository.DonorResponseRepository;
import com.blooddonor.service.events.DonationEvents;
import com.blooddonor.util.GeoUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Emergency request lifecycle:
 * create (auto-notify nearby donors) -> donors accept/decline -> MATCHED -> donations -> FULFILLED.
 * Scheduled jobs widen the radius for slow requests and expire overdue ones.
 */
@Service
@Transactional
public class BloodRequestService {

    private static final Logger log = LoggerFactory.getLogger(BloodRequestService.class);

    private final BloodRequestRepository requestRepository;
    private final DonorRepository donorRepository;
    private final DonorResponseRepository responseRepository;
    private final HospitalService hospitalService;
    private final DonorMatchingService matchingService;
    private final EligibilityService eligibilityService;
    private final NotificationService notifier;
    private final AppProperties props;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public BloodRequestService(BloodRequestRepository requestRepository,
                               DonorRepository donorRepository,
                               DonorResponseRepository responseRepository,
                               HospitalService hospitalService,
                               DonorMatchingService matchingService,
                               EligibilityService eligibilityService,
                               NotificationService notifier,
                               AppProperties props,
                               Clock clock,
                               ApplicationEventPublisher events) {
        this.events = events;
        this.requestRepository = requestRepository;
        this.donorRepository = donorRepository;
        this.responseRepository = responseRepository;
        this.hospitalService = hospitalService;
        this.matchingService = matchingService;
        this.eligibilityService = eligibilityService;
        this.notifier = notifier;
        this.props = props;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- create & read

    public BloodRequestView create(CreateBloodRequest req) {
        return create(req, false);
    }

    /** Raised by the blood bank when stock falls below its minimum. */
    public BloodRequestView createRestock(Long hospitalId, BloodGroup group, int units, Urgency urgency) {
        return create(new CreateBloodRequest(hospitalId, "Blood bank restock", group, units, urgency,
                now().plusHours(48)), true);
    }

    private BloodRequestView create(CreateBloodRequest req, boolean restock) {
        if (req.hospitalId() == null) {
            throw new IllegalArgumentException("hospitalId is required");
        }
        Hospital hospital = hospitalService.find(req.hospitalId());

        BloodRequest request = new BloodRequest();
        request.setHospital(hospital);
        request.setPatientName(req.patientName().trim());
        request.setBloodGroup(req.bloodGroup());
        request.setUnitsNeeded(req.unitsNeeded());
        request.setUrgency(req.urgency());
        request.setNeededBy(req.neededBy());
        request.setCreatedAt(now());
        request.setRestock(restock);
        double radius = req.urgency() == Urgency.CRITICAL ? props.initialRadiusKm() * 2 : props.initialRadiusKm();
        request.setSearchRadiusKm(Math.min(radius, props.maxRadiusKm()));
        requestRepository.save(request);

        int notified = notifyNewDonors(request);
        log.info("Request #{} ({} {} units) created, {} donors notified within {} km",
                request.getId(), request.getBloodGroup().label(), request.getUnitsNeeded(),
                notified, request.getSearchRadiusKm());
        live("request.created", request, notified + " donor(s) notified for request #" + request.getId());
        return toView(request);
    }

    @Transactional(readOnly = true)
    public BloodRequestView get(Long id) {
        return toView(find(id));
    }

    @Transactional(readOnly = true)
    public List<BloodRequestView> list(RequestStatus status) {
        return list(status, null);
    }

    /** hospitalId null = all hospitals. */
    @Transactional(readOnly = true)
    public List<BloodRequestView> list(RequestStatus status, Long hospitalId) {
        List<BloodRequest> requests;
        if (hospitalId == null) {
            requests = status == null
                    ? requestRepository.findAllByOrderByCreatedAtDesc()
                    : requestRepository.findByStatusOrderByCreatedAtDesc(status);
        } else {
            requests = status == null
                    ? requestRepository.findByHospitalIdOrderByCreatedAtDesc(hospitalId)
                    : requestRepository.findByHospitalIdAndStatusOrderByCreatedAtDesc(hospitalId, status);
        }
        return requests.stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public Long hospitalIdOf(Long requestId) {
        return find(requestId).getHospital().getId();
    }

    /** Requests this donor was notified about, still-active ones first. */
    @Transactional(readOnly = true)
    public List<RequestDtos.DonorInboxItem> inbox(Long donorId) {
        return responseRepository.findByDonorIdOrderByNotifiedAtDesc(donorId).stream()
                .sorted(Comparator.comparing((DonorResponse r) -> r.getRequest().getStatus().isClosed()))
                .map(this::toInboxItem)
                .toList();
    }

    @Transactional(readOnly = true)
    public RequestDtos.DonorInboxItem inboxItem(Long requestId, Long donorId) {
        return toInboxItem(findResponse(find(requestId), donorId));
    }

    // ---------------------------------------------------------------- donor actions

    public BloodRequestView accept(Long requestId, Long donorId) {
        BloodRequest request = lock(requestId);
        if (request.getStatus() == RequestStatus.MATCHED) {
            throw new BusinessRuleException("Enough donors have already accepted this request. Thank you!");
        }
        requireStatus(request, RequestStatus.OPEN);

        DonorResponse response = findResponse(request, donorId);
        if (response.getStatus() == ResponseStatus.ACCEPTED || response.getStatus() == ResponseStatus.DONATED) {
            throw new BusinessRuleException("Donor has already " + response.getStatus().name().toLowerCase());
        }
        Eligibility eligibility = eligibilityService.check(response.getDonor());
        if (!eligibility.eligible()) {
            throw new BusinessRuleException("Donor is not eligible: " + String.join("; ", eligibility.reasons()));
        }

        // One active commitment at a time: a donor can't promise blood to two requests at once.
        donorRepository.findByIdForUpdate(donorId);
        List<DonorResponse> otherCommitments = responseRepository
                .findByDonorIdAndStatusAndRequestIdNotAndRequestStatusIn(donorId, ResponseStatus.ACCEPTED,
                        request.getId(), EnumSet.of(RequestStatus.OPEN, RequestStatus.MATCHED));
        if (!otherCommitments.isEmpty()) {
            BloodRequest other = otherCommitments.getFirst().getRequest();
            throw new BusinessRuleException("Donor has already accepted request #" + other.getId()
                    + " at " + other.getHospital().getName()
                    + ". Decline that one first to accept this request.");
        }

        response.setStatus(ResponseStatus.ACCEPTED);
        response.setRespondedAt(now());
        Donor donor = response.getDonor();
        notifier.send(request.getHospital().getPhone(),
                "Donor " + donor.getFullName() + " (" + donor.getBloodGroup().label() + ", "
                        + GeoUtils.round1(response.getDistanceKm()) + " km away) accepted request #"
                        + request.getId() + ". Contact: " + donor.getPhone());

        if (committedDonors(request) >= request.getUnitsNeeded()) {
            request.setStatus(RequestStatus.MATCHED);
            log.info("Request #{} is now MATCHED", request.getId());
        }
        live("request.updated", request, "✅ " + donor.getFullName() + " accepted request #" + request.getId());
        return toView(request);
    }

    public BloodRequestView decline(Long requestId, Long donorId) {
        BloodRequest request = lock(requestId);
        requireStatus(request, RequestStatus.OPEN, RequestStatus.MATCHED);

        DonorResponse response = findResponse(request, donorId);
        if (response.getStatus() != ResponseStatus.NOTIFIED && response.getStatus() != ResponseStatus.ACCEPTED) {
            throw new BusinessRuleException("Cannot decline after status " + response.getStatus());
        }
        boolean wasAccepted = response.getStatus() == ResponseStatus.ACCEPTED;
        response.setStatus(ResponseStatus.DECLINED);
        response.setRespondedAt(now());

        // An accepted donor backed out: reopen and look for a replacement right away.
        if (wasAccepted && request.getStatus() == RequestStatus.MATCHED
                && committedDonors(request) < request.getUnitsNeeded()) {
            request.setStatus(RequestStatus.OPEN);
            int notified = notifyNewDonors(request);
            log.info("Request #{} reopened after a donor backed out, {} new donors notified",
                    request.getId(), notified);
        }
        live("request.updated", request, response.getDonor().getFullName() + " declined request #" + request.getId());
        return toView(request);
    }

    /** Called by the hospital once blood is actually collected from the donor. */
    public BloodRequestView markDonated(Long requestId, Long donorId) {
        BloodRequest request = lock(requestId);
        requireStatus(request, RequestStatus.OPEN, RequestStatus.MATCHED);

        DonorResponse response = findResponse(request, donorId);
        if (response.getStatus() != ResponseStatus.ACCEPTED) {
            throw new BusinessRuleException("Only a donor who accepted can be marked as donated (current: "
                    + response.getStatus() + ")");
        }
        response.setStatus(ResponseStatus.DONATED);
        response.setDonatedAt(now());
        response.getDonor().setLastDonationDate(eligibilityService.today());
        if (request.isRestock()) {
            events.publishEvent(new DonationEvents.UnitsCollected(request.getHospital().getId(),
                    response.getDonor().getBloodGroup(), 1, "restock request #" + request.getId()));
        }
        notifier.send(response.getDonor().getPhone(),
                "Thank you for donating blood at " + request.getHospital().getName()
                        + "! You can donate again after " + props.minDaysBetweenDonations() + " days.");

        if (request.countResponses(ResponseStatus.DONATED) >= request.getUnitsNeeded()) {
            close(request, RequestStatus.FULFILLED, "Request #" + request.getId()
                    + " has been fulfilled. You are no longer needed for it. Thank you!");
        } else if (committedDonors(request) >= request.getUnitsNeeded()) {
            request.setStatus(RequestStatus.MATCHED);
        }
        live("request.updated", request, null);
        return toView(request);
    }

    public BloodRequestView cancel(Long requestId) {
        BloodRequest request = lock(requestId);
        if (request.getStatus().isClosed()) {
            throw new BusinessRuleException("Request is already " + request.getStatus());
        }
        close(request, RequestStatus.CANCELLED,
                "Request #" + request.getId() + " at " + request.getHospital().getName()
                        + " was cancelled. No need to come. Thank you!");
        live("request.updated", request, null);
        return toView(request);
    }

    // ---------------------------------------------------------------- scheduled work

    /** OPEN requests with no luck for a while: widen the radius and notify the newly reached donors. */
    public int expandStaleSearches() {
        LocalDateTime threshold = now().minusMinutes(props.expandAfterMinutes());
        List<BloodRequest> stale = requestRepository.findByStatusAndLastSearchAtBefore(RequestStatus.OPEN, threshold);
        for (BloodRequest request : stale) {
            double oldRadius = request.getSearchRadiusKm();
            request.setSearchRadiusKm(Math.min(oldRadius + props.radiusStepKm(), props.maxRadiusKm()));
            int notified = notifyNewDonors(request);
            log.info("Request #{}: radius {} -> {} km, {} new donors notified",
                    request.getId(), oldRadius, request.getSearchRadiusKm(), notified);
            live("request.updated", request, notified > 0 ? "Search for request #" + request.getId()
                    + " widened to " + request.getSearchRadiusKm() + " km, " + notified + " more donor(s) notified" : null);
        }
        return stale.size();
    }

    public int expireOverdue() {
        List<BloodRequest> overdue = requestRepository.findByStatusInAndNeededByBefore(
                EnumSet.of(RequestStatus.OPEN, RequestStatus.MATCHED), now());
        for (BloodRequest request : overdue) {
            close(request, RequestStatus.EXPIRED, "Request #" + request.getId() + " has expired. Thank you!");
            notifier.send(request.getHospital().getPhone(),
                    "Request #" + request.getId() + " expired with " + request.countResponses(ResponseStatus.DONATED)
                            + "/" + request.getUnitsNeeded() + " units donated.");
            live("request.updated", request, "Request #" + request.getId() + " expired");
        }
        return overdue.size();
    }

    // ---------------------------------------------------------------- helpers

    /** Finds donors not yet contacted for this request and notifies them. */
    private int notifyNewDonors(BloodRequest request) {
        Set<Long> alreadyContacted = request.getResponses().stream()
                .map(r -> r.getDonor().getId())
                .collect(Collectors.toSet());
        Hospital hospital = request.getHospital();

        List<DonorMatchingService.Match> matches = matchingService.findNearbyEligible(
                request.getBloodGroup(), hospital.getLatitude(), hospital.getLongitude(),
                request.getSearchRadiusKm(), props.maxDonorsPerSearch(), alreadyContacted);

        for (DonorMatchingService.Match match : matches) {
            DonorResponse response = new DonorResponse();
            response.setRequest(request);
            response.setDonor(match.donor());
            response.setDistanceKm(match.distanceKm());
            response.setNotifiedAt(now());
            request.getResponses().add(response);

            notifier.send(match.donor().getPhone(),
                    "[" + request.getUrgency() + "] " + request.getBloodGroup().label() + " blood needed at "
                            + hospital.getName() + " (" + GeoUtils.round1(match.distanceKm()) + " km away). "
                            + "Request #" + request.getId() + ". Please accept or decline in the app.");
        }
        if (!matches.isEmpty()) {
            events.publishEvent(new PushMessage(
                    matches.stream().map(m -> m.donor().getId()).collect(Collectors.toSet()),
                    (request.getUrgency() == Urgency.CRITICAL ? "🚨 " : "🩸 ") + request.getBloodGroup().label()
                            + " blood needed nearby",
                    hospital.getName() + " needs " + request.getUnitsNeeded() + " unit(s)"
                            + (request.getUrgency() == Urgency.NORMAL ? "" : " (" + request.getUrgency() + ")")
                            + ". Tap to accept or decline.",
                    "/donor", "request-" + request.getId(), request.getUrgency() != Urgency.NORMAL));
        }
        request.setLastSearchAt(now());
        return matches.size();
    }

    private void close(BloodRequest request, RequestStatus status, String messageToPendingDonors) {
        request.setStatus(status);
        request.setClosedAt(now());
        request.getResponses().stream()
                .filter(r -> r.getStatus() == ResponseStatus.NOTIFIED || r.getStatus() == ResponseStatus.ACCEPTED)
                .forEach(r -> notifier.send(r.getDonor().getPhone(), messageToPendingDonors));
    }

    private long committedDonors(BloodRequest request) {
        return request.countResponses(ResponseStatus.ACCEPTED) + request.countResponses(ResponseStatus.DONATED);
    }

    private void requireStatus(BloodRequest request, RequestStatus... allowed) {
        for (RequestStatus s : allowed) {
            if (request.getStatus() == s) return;
        }
        throw new BusinessRuleException("Request #" + request.getId() + " is " + request.getStatus());
    }

    private DonorResponse findResponse(BloodRequest request, Long donorId) {
        return request.getResponses().stream()
                .filter(r -> r.getDonor().getId().equals(donorId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Donor " + donorId + " for request", request.getId()));
    }

    private BloodRequest find(Long id) {
        return requestRepository.findById(id).orElseThrow(() -> new NotFoundException("Blood request", id));
    }

    private BloodRequest lock(Long id) {
        return requestRepository.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Blood request", id));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /**
     * Tell open screens about a change (sent after commit). The hospital gets a readable message;
     * donors only get ids and status, never other donors' names.
     */
    private void live(String type, BloodRequest r, String hospitalMessage) {
        Map<String, Object> base = Map.of("requestId", r.getId(), "status", r.getStatus().name());
        Map<String, Object> forHospital = new HashMap<>(base);
        if (hospitalMessage != null) forHospital.put("message", hospitalMessage);
        events.publishEvent(new LiveEvent(type, Set.of(r.getHospital().getId()), Set.of(), forHospital));

        Set<Long> donorIds = r.getResponses().stream().map(x -> x.getDonor().getId()).collect(Collectors.toSet());
        if (!donorIds.isEmpty()) events.publishEvent(new LiveEvent(type, Set.of(), donorIds, base));
    }

    private BloodRequestView toView(BloodRequest r) {
        List<ResponseView> donors = r.getResponses().stream()
                .sorted(Comparator.comparingInt((DonorResponse resp) -> displayRank(resp.getStatus()))
                        .thenComparingDouble(DonorResponse::getDistanceKm))
                .map(this::toResponseView)
                .toList();
        return new BloodRequestView(
                r.getId(), r.getHospital().getId(), r.getHospital().getName(), r.getPatientName(),
                r.getBloodGroup(), r.getBloodGroup().label(), r.getUnitsNeeded(), r.getUrgency(), r.getStatus(),
                r.getSearchRadiusKm(), r.getNeededBy(), r.getCreatedAt(), r.getClosedAt(),
                r.getResponses().size(),
                r.countResponses(ResponseStatus.ACCEPTED),
                r.countResponses(ResponseStatus.DONATED),
                donors,
                r.isRestock());
    }

    private RequestDtos.DonorInboxItem toInboxItem(DonorResponse resp) {
        BloodRequest r = resp.getRequest();
        Hospital h = r.getHospital();
        boolean revealPhone = resp.getStatus() == ResponseStatus.ACCEPTED || resp.getStatus() == ResponseStatus.DONATED;
        return new RequestDtos.DonorInboxItem(r.getId(), h.getName(), h.getAddress(),
                revealPhone ? h.getPhone() : null, r.getBloodGroup().label(), r.getUrgency(), r.getStatus(),
                resp.getStatus(), GeoUtils.round1(resp.getDistanceKm()), r.getNeededBy(), resp.getNotifiedAt());
    }

    /** Hospital view order: donors who are coming first, then pending, then declined. */
    private static int displayRank(ResponseStatus status) {
        return switch (status) {
            case DONATED -> 0;
            case ACCEPTED -> 1;
            case NOTIFIED -> 2;
            case DECLINED -> 3;
        };
    }

    private ResponseView toResponseView(DonorResponse resp) {
        Donor d = resp.getDonor();
        boolean revealPhone = resp.getStatus() == ResponseStatus.ACCEPTED || resp.getStatus() == ResponseStatus.DONATED;
        return new ResponseView(d.getId(), d.getFullName(), d.getBloodGroup(), GeoUtils.round1(resp.getDistanceKm()),
                resp.getStatus(), revealPhone ? d.getPhone() : DonorService.maskPhone(d.getPhone()),
                resp.getNotifiedAt(), resp.getRespondedAt(), resp.getDonatedAt(),
                revealPhone ? Math.round(d.getLatitude() * 100) / 100.0 : null,
                revealPhone ? Math.round(d.getLongitude() * 100) / 100.0 : null);
    }
}
