package com.blooddonor.security;

import com.blooddonor.dto.AdminDtos.AccountRow;
import com.blooddonor.dto.AdminDtos.AuditRow;
import com.blooddonor.dto.AdminDtos.PageResult;
import com.blooddonor.dto.AdminDtos.PasswordReset;
import com.blooddonor.entity.AuditEvent;
import com.blooddonor.entity.Role;
import com.blooddonor.entity.UserAccount;
import com.blooddonor.exception.BusinessRuleException;
import com.blooddonor.exception.NotFoundException;
import com.blooddonor.repository.UserAccountRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

/**
 * Admin tools for login accounts: search, disable/enable (with instant logout) and password reset.
 * Every change is written to the audit log.
 */
@Service
@Transactional
public class AdminUserService {

    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private final UserAccountRepository accounts;
    private final RefreshTokenService refreshTokens;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();

    public AdminUserService(UserAccountRepository accounts, RefreshTokenService refreshTokens,
                            PasswordEncoder encoder, AuditService audit) {
        this.accounts = accounts;
        this.refreshTokens = refreshTokens;
        this.encoder = encoder;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResult<AccountRow> search(String q, Role role, Boolean enabled, int page, int size) {
        String pattern = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        Page<UserAccount> result = accounts.search(pattern, role, enabled,
                PageRequest.of(Math.max(0, page), Math.clamp(size, 1, 100)));
        return new PageResult<>(result.getContent().stream().map(this::toRow).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements());
    }

    /** Blocks the account and logs it out everywhere at once. */
    public AccountRow disable(Long id, String actor, String reason) {
        UserAccount a = find(id);
        if (a.getUsername().equals(actor)) throw new BusinessRuleException("You can't disable your own account");
        if (!a.isEnabled()) throw new BusinessRuleException(a.getUsername() + " is already disabled");
        if (a.getRole() == Role.ADMIN && accounts.countByRoleAndEnabledTrue(Role.ADMIN) <= 1) {
            throw new BusinessRuleException("You can't disable the last admin");
        }
        a.setEnabled(false);
        long closed = refreshTokens.revokeAllFor(a);
        audit.record(actor, AuditEvent.Action.ACCOUNT_DISABLED, a.getUsername(),
                (reason == null || reason.isBlank() ? "no reason given" : reason.trim()) + "; " + closed + " session(s) closed");
        return toRow(a);
    }

    public AccountRow enable(Long id, String actor) {
        UserAccount a = find(id);
        if (a.isEnabled()) throw new BusinessRuleException(a.getUsername() + " is already enabled");
        a.setEnabled(true);
        audit.record(actor, AuditEvent.Action.ACCOUNT_ENABLED, a.getUsername(), null);
        return toRow(a);
    }

    /** Sets a random temporary password, shown to the admin once, and logs the account out everywhere. */
    public PasswordReset resetPassword(Long id, String actor) {
        UserAccount a = find(id);
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) sb.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
        String temporary = sb.toString();
        a.setPasswordHash(encoder.encode(temporary));
        long closed = refreshTokens.revokeAllFor(a);
        audit.record(actor, AuditEvent.Action.PASSWORD_RESET, a.getUsername(), closed + " session(s) closed");
        return new PasswordReset(a.getUsername(), temporary, closed);
    }

    @Transactional(readOnly = true)
    public PageResult<AuditRow> auditLog(String user, AuditEvent.Action action, int page, int size) {
        var result = audit.search(user, action, page, size);
        return new PageResult<>(result.getContent().stream()
                .map(e -> new AuditRow(e.getId(), e.getAt(), e.getActor(), e.getAction(), e.getTarget(), e.getDetails(), e.getIp()))
                .toList(), result.getNumber(), result.getSize(), result.getTotalElements());
    }

    private UserAccount find(Long id) {
        return accounts.findById(id).orElseThrow(() -> new NotFoundException("Account", id));
    }

    private AccountRow toRow(UserAccount a) {
        String name = a.getDonor() != null ? a.getDonor().getFullName()
                : a.getHospital() != null ? a.getHospital().getName() : "Administrator";
        return new AccountRow(a.getId(), a.getUsername(), a.getRole(), name,
                a.getDonor() == null ? null : a.getDonor().getId(),
                a.getHospital() == null ? null : a.getHospital().getId(),
                a.isEnabled(), a.getCreatedAt(), a.getLastLoginAt(), refreshTokens.activeSessions(a.getUsername()));
    }
}
