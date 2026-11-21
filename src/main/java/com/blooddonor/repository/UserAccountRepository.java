package com.blooddonor.repository;

import com.blooddonor.entity.Role;
import com.blooddonor.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByRole(Role role);

    boolean existsByDonorId(Long donorId);

    boolean existsByHospitalId(Long hospitalId);
}
