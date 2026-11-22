package com.blooddonor.repository;

import com.blooddonor.entity.ChatMessage;
import com.blooddonor.entity.ChatMessage.Sender;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findByResponseIdOrderByCreatedAtAscIdAsc(Long responseId);

    long countByResponseIdAndSenderAndCreatedAtAfter(Long responseId, Sender sender, LocalDateTime after);

    /** Marks everything the other side sent as read. */
    @Modifying
    @Query("update ChatMessage m set m.readAt = :now where m.response.id = :responseId and m.sender = :from and m.readAt is null")
    int markRead(@Param("responseId") Long responseId, @Param("from") Sender from, @Param("now") LocalDateTime now);

    /** [requestId, count] of unread messages from the hospital, for one donor. */
    @Query("""
            select r.request.id, count(m) from ChatMessage m join m.response r
            where r.donor.id = :donorId and m.sender = 'HOSPITAL' and m.readAt is null
            group by r.request.id""")
    List<Object[]> unreadForDonor(@Param("donorId") Long donorId);

    /** [requestId, donorId, count] of unread messages from donors, for one hospital (null = all hospitals). */
    @Query("""
            select r.request.id, r.donor.id, count(m) from ChatMessage m join m.response r
            where (:hospitalId is null or r.request.hospital.id = :hospitalId)
              and m.sender = 'DONOR' and m.readAt is null
            group by r.request.id, r.donor.id""")
    List<Object[]> unreadForHospital(@Param("hospitalId") Long hospitalId);
}
