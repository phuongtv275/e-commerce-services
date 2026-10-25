package com.example.inventoryservice.repository;

import com.example.inventoryservice.entity.ReservationItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReservationItemRepository extends JpaRepository<ReservationItem, UUID> {
    List<ReservationItem> findByReservationId(UUID reservationId);
}
