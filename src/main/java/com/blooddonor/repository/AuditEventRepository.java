package com.blooddonor.repository;

import com.blooddonor.entity.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    /** Newest first; any filter left null is ignored. user matches actor or target. */
    @Query("""
            select e from AuditEvent e
            where (:user is null or e.actor = :user or e.target = :user)
              and (:action is null or e.action = :action)
            order by e.at desc, e.id desc""")
    Page<AuditEvent> search(@Param("user") String user, @Param("action") AuditEvent.Action action, Pageable page);
}
