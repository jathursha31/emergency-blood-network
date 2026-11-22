package com.blooddonor.repository;

import com.blooddonor.entity.CampBooking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

public interface CampBookingRepository extends JpaRepository<CampBooking, Long> {

    long countByCampIdAndSlotTimeAndStatusNot(Long campId, LocalTime slotTime, CampBooking.Status status);

    boolean existsByCampIdAndDonorIdAndStatusNot(Long campId, Long donorId, CampBooking.Status status);

    boolean existsByCode(String code);

    Optional<CampBooking> findByCode(String code);

    List<CampBooking> findByDonorIdOrderByBookedAtDesc(Long donorId);

    List<CampBooking> findByCampIdOrderBySlotTimeAscBookedAtAsc(Long campId);

    List<CampBooking> findByDonorIdAndStatusOrderByCheckedInAtDesc(Long donorId, CampBooking.Status status);

    long countByDonorIdAndStatus(Long donorId, CampBooking.Status status);

    /** [donorId, count] of bookings in this status. */
    @Query("select b.donor.id, count(b) from CampBooking b where b.status = :status group by b.donor.id")
    List<Object[]> countByDonorGrouped(@Param("status") CampBooking.Status status);
}
