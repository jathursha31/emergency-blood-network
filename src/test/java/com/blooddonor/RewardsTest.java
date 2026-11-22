package com.blooddonor;

import com.blooddonor.dto.DonorDtos.DonorView;
import com.blooddonor.dto.RewardDtos.MyRewards;
import com.blooddonor.entity.Badge;
import com.blooddonor.entity.BloodGroup;
import com.blooddonor.repository.DonorRepository;
import com.blooddonor.service.BloodRequestService;
import com.blooddonor.service.DonorService;
import com.blooddonor.service.HospitalService;
import com.blooddonor.service.RewardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class RewardsTest {

    @Autowired HospitalService hospitalService;
    @Autowired DonorService donorService;
    @Autowired BloodRequestService requestService;
    @Autowired RewardService rewardService;
    @Autowired DonorRepository donorRepository;

    Fixtures fx;
    Long hospitalId;

    @BeforeEach
    void setUp() {
        fx = new Fixtures(hospitalService, donorService, requestService);
        hospitalId = fx.hospital("Reward Hospital");
    }

    @Test
    void badgeLevels() {
        assertThat(Badge.forCount(0)).isEqualTo(Badge.NONE);
        assertThat(Badge.forCount(1)).isEqualTo(Badge.FIRST_DROP);
        assertThat(Badge.forCount(4)).isEqualTo(Badge.BRONZE);
        assertThat(Badge.forCount(10)).isEqualTo(Badge.HERO);
        assertThat(Badge.forCount(100)).isEqualTo(Badge.LEGEND);
        assertThat(Badge.LEGEND.next()).isNull();
    }

    @Test
    void newDonorHasNoBadge() {
        DonorView d = fx.donor("New Person", BloodGroup.A_POS);
        MyRewards r = rewardService.forDonor(d.id());
        assertThat(r.donations()).isZero();
        assertThat(r.badge().badge()).isEqualTo(Badge.NONE);
        assertThat(r.nextBadge().badge()).isEqualTo(Badge.FIRST_DROP);
        assertThat(r.donationsToNext()).isEqualTo(1);
        assertThat(r.leaderboardRank()).isZero();
    }

    @Test
    void donationEarnsFirstDropAndLeaderboardPlace() {
        DonorView a = fx.donor("Kavin Raj", BloodGroup.AB_POS);
        DonorView b = fx.donor("Mala Devi", BloodGroup.AB_POS);
        fx.donate(hospitalId, a);
        // allow a second donation for the same donor by moving the last donation back 91 days
        donorRepository.findById(a.id()).orElseThrow().setLastDonationDate(java.time.LocalDate.now().minusDays(91));
        fx.donate(hospitalId, a);
        fx.donate(hospitalId, b);

        MyRewards ra = rewardService.forDonor(a.id());
        assertThat(ra.donations()).isEqualTo(2);
        assertThat(ra.livesHelped()).isEqualTo(6);
        assertThat(ra.badge().badge()).isEqualTo(Badge.FIRST_DROP);
        assertThat(ra.donationsToNext()).isEqualTo(1);

        var board = rewardService.publicLeaderboard(50);
        var kavin = board.stream().filter(e -> e.displayName().equals("Kavin R.")).findFirst().orElseThrow();
        var mala = board.stream().filter(e -> e.displayName().equals("Mala D.")).findFirst().orElseThrow();
        assertThat(kavin.donations()).isEqualTo(2);
        assertThat(kavin.rank()).isLessThan(mala.rank());
        assertThat(ra.leaderboardRank()).isEqualTo(kavin.rank());
    }
}
