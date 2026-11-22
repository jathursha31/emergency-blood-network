package com.blooddonor.service;

import com.blooddonor.config.AppProperties;
import com.blooddonor.dto.DonorDtos.Eligibility;
import com.blooddonor.entity.Donor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Donation rules: minimum gap between donations, age range, and the donor's own availability switch. */
@Service
public class EligibilityService {

    private final AppProperties props;
    private final Clock clock;

    public EligibilityService(AppProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public Eligibility check(Donor donor) {
        return checkOn(donor, today());
    }

    /** Can this donor give blood on the given (today or future) date, e.g. a camp day? */
    public Eligibility checkOn(Donor donor, LocalDate date) {
        LocalDate today = today();
        List<String> reasons = new ArrayList<>();

        int age = Period.between(donor.getDateOfBirth(), date).getYears();
        if (age < props.minDonorAge()) {
            reasons.add("Donor must be at least " + props.minDonorAge() + " years old (current age " + age + ")");
        }
        if (age > props.maxDonorAge()) {
            reasons.add("Donor must be at most " + props.maxDonorAge() + " years old (current age " + age + ")");
        }
        if (!donor.isAvailable()) {
            reasons.add("Donor has marked themselves unavailable");
        }

        LocalDate nextEligible = nextEligibleDate(donor);
        long daysRemaining = Math.max(0, ChronoUnit.DAYS.between(today, nextEligible));
        if (nextEligible.isAfter(date)) {
            reasons.add("Last donation was on " + donor.getLastDonationDate()
                    + "; must wait " + props.minDaysBetweenDonations() + " days between donations");
        }
        return new Eligibility(reasons.isEmpty(), reasons, nextEligible, daysRemaining);
    }

    public LocalDate nextEligibleDate(Donor donor) {
        if (donor.getLastDonationDate() == null) return today();
        LocalDate next = donor.getLastDonationDate().plusDays(props.minDaysBetweenDonations());
        return next.isBefore(today()) ? today() : next;
    }

    /** A donor whose last donation is on/before this date has waited long enough. */
    public LocalDate lastDonationCutoff() {
        return today().minusDays(props.minDaysBetweenDonations());
    }

    /** Oldest allowed date of birth (maxDonorAge, inclusive of the whole year of that age). */
    public LocalDate minDateOfBirth() {
        return today().minusYears(props.maxDonorAge() + 1L).plusDays(1);
    }

    /** Youngest allowed date of birth. */
    public LocalDate maxDateOfBirth() {
        return today().minusYears(props.minDonorAge());
    }
}
