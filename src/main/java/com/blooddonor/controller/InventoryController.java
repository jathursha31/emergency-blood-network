package com.blooddonor.controller;

import com.blooddonor.dto.InventoryDtos.*;
import com.blooddonor.entity.BloodGroup;
import com.blooddonor.exception.ForbiddenException;
import com.blooddonor.security.CurrentUser;
import com.blooddonor.service.InventoryService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Hospital blood bank. A HOSPITAL works on its own stock; an ADMIN passes ?hospitalId=.
 */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventory;

    public InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @GetMapping
    public List<StockView> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long hospitalId) {
        return inventory.list(hospitalFor(jwt, hospitalId));
    }

    /** ADMIN: every hospital's stock. */
    @GetMapping("/overview")
    public List<HospitalStock> overview(@AuthenticationPrincipal Jwt jwt) {
        if (!CurrentUser.from(jwt).isAdmin()) throw new ForbiddenException("Admins only");
        return inventory.overview();
    }

    /** Set units, alert level and/or auto-request for one blood group. */
    @PutMapping("/{group}")
    public StockView update(@AuthenticationPrincipal Jwt jwt, @PathVariable BloodGroup group,
                            @RequestParam(required = false) Long hospitalId, @Valid @RequestBody UpdateStock req) {
        return inventory.update(hospitalFor(jwt, hospitalId), group, req);
    }

    /** Units issued to patients. May raise a restock request. */
    @PostMapping("/{group}/use")
    public StockView use(@AuthenticationPrincipal Jwt jwt, @PathVariable BloodGroup group,
                         @RequestParam(required = false) Long hospitalId, @Valid @RequestBody ChangeUnits req) {
        return inventory.use(hospitalFor(jwt, hospitalId), group, req.units());
    }

    /** Units received from outside (another blood bank, etc.). */
    @PostMapping("/{group}/add")
    public StockView add(@AuthenticationPrincipal Jwt jwt, @PathVariable BloodGroup group,
                         @RequestParam(required = false) Long hospitalId, @Valid @RequestBody ChangeUnits req) {
        return inventory.add(hospitalFor(jwt, hospitalId), group, req.units());
    }

    private Long hospitalFor(Jwt jwt, Long requested) {
        CurrentUser user = CurrentUser.from(jwt);
        if (user.isAdmin()) {
            if (requested == null) throw new IllegalArgumentException("hospitalId is required for admins");
            return requested;
        }
        if (requested != null && !requested.equals(user.hospitalId())) {
            throw new ForbiddenException("A hospital can only manage its own blood bank");
        }
        return user.hospitalId();
    }
}
