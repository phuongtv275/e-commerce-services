package com.example.inventoryservice.repository;

import com.example.inventoryservice.entity.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    Optional<Reservation> findByOrderId(UUID orderId);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE reservations
            SET status = 'COMMITTED',
                committed_at = :now
            WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    int transitionToCommitted(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE reservations
            SET status = 'RELEASED',
                released_at = :now
            WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    int transitionToReleased(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE reservations
            SET status = 'EXPIRED',
                released_at = :now
            WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    int transitionToExpired(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE reservations
            SET status = 'RESTOCKED',
                released_at = :now
            WHERE id = :id AND status = 'COMMITTED'
            """, nativeQuery = true)
    int transitionToRestocked(@Param("id") UUID id, @Param("now") Instant now);

    @Query(value = """
            SELECT id FROM reservations
            WHERE status = 'PENDING'
              AND expires_at < :now
            ORDER BY expires_at ASC, id ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> findExpiredReservationIdsForUpdate(@Param("now") Instant now, @Param("limit") int limit);
}
