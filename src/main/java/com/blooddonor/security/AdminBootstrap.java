package com.blooddonor.security;

import com.blooddonor.entity.Role;
import com.blooddonor.repository.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Makes sure there is always one ADMIN login (from app.security.admin-username / admin-password). */
@Component
@Order(1)
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserAccountRepository accounts;
    private final AuthService authService;
    private final SecurityProperties props;

    public AdminBootstrap(UserAccountRepository accounts, AuthService authService, SecurityProperties props) {
        this.accounts = accounts;
        this.authService = authService;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (accounts.existsByRole(Role.ADMIN)) return;
        authService.createAccount(props.adminUsername(), props.adminPassword(), Role.ADMIN, null, null);
        log.info("Created admin account '{}'", props.adminUsername());
    }
}
