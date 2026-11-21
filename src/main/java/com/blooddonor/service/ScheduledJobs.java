package com.blooddonor.service;

import com.blooddonor.entity.Donor;
import com.blooddonor.repository.DonorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Background jobs. Cron expressions live in application.properties ("-" disables a job).
 */
@Component
public class ScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);

    private final BloodRequestService requestService;
    private final DonorRepository donorRepository;
    private final EligibilityService eligibilityService;
    private final NotificationService notifier;

    public ScheduledJobs(BloodRequestService requestService,
                         DonorRepository donorRepository,
                         EligibilityService eligibilityService,
                         NotificationService notifier) {
        this.requestService = requestService;
        this.donorRepository = donorRepository;
        this.eligibilityService = eligibilityService;
        this.notifier = notifier;
    }

    /** Widen the search radius for OPEN requests that have not found enough donors. */
    @Scheduled(cron = "${jobs.expand-search.cron}")
    public void expandStaleSearches() {
        int count = requestService.expandStaleSearches();
        if (count > 0) log.info("Expanded search for {} request(s)", count);
    }

    /** Close requests whose neededBy time has passed. */
    @Scheduled(cron = "${jobs.expire-requests.cron}")
    public void expireOverdueRequests() {
        int count = requestService.expireOverdue();
        if (count > 0) log.info("Expired {} request(s)", count);
    }

    /** Every morning: tell donors whose waiting period ends today that they can donate again. */
    @Scheduled(cron = "${jobs.eligibility-reminder.cron}")
    @Transactional(readOnly = true)
    public void remindNewlyEligibleDonors() {
        List<Donor> donors = donorRepository.findByAvailableTrueAndLastDonationDate(
                eligibilityService.lastDonationCutoff());
        donors.forEach(d -> notifier.send(d.getPhone(),
                "Hi " + d.getFullName() + ", you are eligible to donate blood again from today. "
                        + "Your " + d.getBloodGroup().label() + " blood can save a life!"));
        log.info("Eligibility reminder sent to {} donor(s)", donors.size());
    }
}
