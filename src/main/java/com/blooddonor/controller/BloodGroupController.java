package com.blooddonor.controller;

import com.blooddonor.entity.BloodGroup;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/blood-groups")
public class BloodGroupController {

    public record CompatibilityView(BloodGroup group, String label,
                                    List<String> canReceiveFrom, List<String> canDonateTo) {
    }

    @GetMapping
    public List<CompatibilityView> all() {
        return Arrays.stream(BloodGroup.values()).map(this::view).toList();
    }

    @GetMapping("/{group}")
    public CompatibilityView one(@PathVariable BloodGroup group) {
        return view(group);
    }

    private CompatibilityView view(BloodGroup g) {
        return new CompatibilityView(g, g.label(),
                g.compatibleDonors().stream().map(BloodGroup::label).toList(),
                Arrays.stream(BloodGroup.values()).filter(g::canDonateTo).map(BloodGroup::label).toList());
    }
}
