package com.blooddonor.controller;

import com.blooddonor.dto.RewardDtos.LeaderboardEntry;
import com.blooddonor.service.RewardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Public top-donors list (short names only). */
@RestController
@RequestMapping("/api/leaderboard")
public class LeaderboardController {

    private final RewardService rewardService;

    public LeaderboardController(RewardService rewardService) {
        this.rewardService = rewardService;
    }

    @GetMapping
    public List<LeaderboardEntry> top(@RequestParam(defaultValue = "10") int limit) {
        return rewardService.publicLeaderboard(Math.clamp(limit, 1, 50));
    }
}
