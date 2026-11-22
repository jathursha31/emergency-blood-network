package com.blooddonor.repository;

import com.blooddonor.entity.OtpCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface OtpCodeRepository extends JpaRepository<OtpCode, Long> {

    Optional<OtpCode> findTopByPhoneAndPurposeOrderByCreatedAtDesc(String phone, OtpCode.Purpose purpose);

    long countByPhoneAndCreatedAtAfter(String phone, LocalDateTime since);
}
