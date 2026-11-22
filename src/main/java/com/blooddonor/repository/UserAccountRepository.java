package com.blooddonor.repository;

import com.blooddonor.entity.Role;
import com.blooddonor.entity.UserAccount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByRole(Role role);

    boolean existsByDonorId(Long donorId);

    boolean existsByHospitalId(Long hospitalId);

    long countByRoleAndEnabledTrue(Role role);

    /** Admin search. q (already lower-cased and wrapped in %) matches username, donor name or hospital name. */
    @Query("""
            select a from UserAccount a left join a.donor d left join a.hospital h
            where (:role is null or a.role = :role)
              and (:enabled is null or a.enabled = :enabled)
              and (:q is null or lower(a.username) like :q or lower(d.fullName) like :q or lower(h.name) like :q)
            order by a.createdAt desc, a.id desc""")
    Page<UserAccount> search(@Param("q") String q, @Param("role") Role role, @Param("enabled") Boolean enabled,
                             Pageable page);
}
