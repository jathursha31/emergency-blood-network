package com.blooddonor.repository;

import com.blooddonor.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("""
            update RefreshToken t set t.revokedAt = :now, t.revokedReason = :reason
            where t.family = :family and t.revokedAt is null""")
    int revokeFamily(@Param("family") String family, @Param("now") LocalDateTime now, @Param("reason") String reason);

    @Modifying
    @Query("""
            update RefreshToken t set t.revokedAt = :now, t.revokedReason = :reason
            where t.account.id = :accountId and t.revokedAt is null""")
    int revokeAllForAccount(@Param("accountId") Long accountId, @Param("now") LocalDateTime now,
                            @Param("reason") String reason);

    @Query("select count(t) from RefreshToken t where t.account.id = :accountId and t.revokedAt is null and t.expiresAt > :now")
    long countActive(@Param("accountId") Long accountId, @Param("now") LocalDateTime now);

    @Query("""
            select count(t) > 0 from RefreshToken t
            where t.family = :family and t.revokedAt is null and t.expiresAt > :now""")
    boolean existsActiveInFamily(@Param("family") String family, @Param("now") LocalDateTime now);

    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") LocalDateTime before);
}
