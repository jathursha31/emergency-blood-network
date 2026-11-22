package com.blooddonor.repository;

import com.blooddonor.entity.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    Optional<PushSubscription> findByEndpoint(String endpoint);

    List<PushSubscription> findByAccountId(Long accountId);

    /** Subscriptions of the donor accounts with these donor ids (enabled accounts only). */
    @Query("""
            select s from PushSubscription s join fetch s.account a
            where a.donor.id in :donorIds and a.enabled = true""")
    List<PushSubscription> findForDonors(@Param("donorIds") Collection<Long> donorIds);
}
