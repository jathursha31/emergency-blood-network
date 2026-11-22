package com.blooddonor.controller;

import com.blooddonor.dto.AdminDtos.*;
import com.blooddonor.entity.AuditEvent;
import com.blooddonor.entity.Role;
import com.blooddonor.security.AdminUserService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** ADMIN only (SecurityConfig: /api/admin/**). */
@RestController
@RequestMapping("/api/admin")
public class AdminUserController {

    private final AdminUserService users;

    public AdminUserController(AdminUserService users) {
        this.users = users;
    }

    /** e.g. /api/admin/users?q=arun&role=DONOR&enabled=true&page=0&size=20 */
    @GetMapping("/users")
    public PageResult<AccountRow> users(@RequestParam(required = false) String q,
                                        @RequestParam(required = false) Role role,
                                        @RequestParam(required = false) Boolean enabled,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return users.search(q, role, enabled, page, size);
    }

    @PostMapping("/users/{id}/disable")
    public AccountRow disable(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                              @Valid @RequestBody(required = false) DisableRequest body) {
        return users.disable(id, jwt.getSubject(), body == null ? null : body.reason());
    }

    @PostMapping("/users/{id}/enable")
    public AccountRow enable(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return users.enable(id, jwt.getSubject());
    }

    @PostMapping("/users/{id}/reset-password")
    public PasswordReset resetPassword(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return users.resetPassword(id, jwt.getSubject());
    }

    /** e.g. /api/admin/audit?user=9840000001&action=LOGIN_FAILED */
    @GetMapping("/audit")
    public PageResult<AuditRow> audit(@RequestParam(required = false) String user,
                                      @RequestParam(required = false) AuditEvent.Action action,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "50") int size) {
        return users.auditLog(user, action, page, size);
    }
}
