package com.example.inventoryservice.scheduler;

import com.example.inventoryservice.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class ReservationExpiryScheduler {

    private final ReservationService reservationService;
    private final Clock clock;

    @Value("${inventory.reservation.expiry-batch-size:${inventory.scheduler.expiry-batch-size:100}}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${inventory.reservation.expiry-interval-ms:${inventory.scheduler.expiry-interval:30000}}",
               initialDelayString = "${inventory.reservation.expiry-initial-delay-ms:${inventory.scheduler.expiry-initial-delay:10000}}")
    public void processExpiredReservations() {
        Instant now = Instant.now(clock);
        try {
            int expired = reservationService.expireDue(now, batchSize);
            if (expired > 0) {
                log.info("Expired {} reservations due before {}", expired, now);
            }
        } catch (Exception e) {
            log.error("Error occurred while processing expired reservations: {}", e.getMessage(), e);
        }
    }
}
