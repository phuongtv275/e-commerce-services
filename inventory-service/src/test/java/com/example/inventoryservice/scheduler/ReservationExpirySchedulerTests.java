package com.example.inventoryservice.scheduler;

import com.example.inventoryservice.service.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationExpirySchedulerTests {

    @Mock
    private ReservationService reservationService;

    private Clock fixedClock;
    private ReservationExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneId.of("UTC"));
        scheduler = new ReservationExpiryScheduler(reservationService, fixedClock);
        ReflectionTestUtils.setField(scheduler, "batchSize", 100);
    }

    @Test
    @DisplayName("Positive: processExpiredReservations calls expireDue with current time and configured batch size")
    void testProcessExpiredReservations_Success() {
        when(reservationService.expireDue(eq(Instant.now(fixedClock)), eq(100))).thenReturn(5);

        scheduler.processExpiredReservations();

        verify(reservationService).expireDue(Instant.now(fixedClock), 100);
    }

    @Test
    @DisplayName("Negative: processExpiredReservations handles service exception gracefully without throwing")
    void testProcessExpiredReservations_HandlesException() {
        when(reservationService.expireDue(any(), eq(100)))
                .thenThrow(new RuntimeException("Database connectivity transient failure"));

        // Does not throw
        scheduler.processExpiredReservations();

        verify(reservationService).expireDue(any(), eq(100));
    }

    @Test
    @DisplayName("Positive: Custom batch size from inventory.reservation.expiry-batch-size is passed to expireDue")
    void testProcessExpiredReservations_CustomBatchSize() {
        ReflectionTestUtils.setField(scheduler, "batchSize", 250);
        when(reservationService.expireDue(eq(Instant.now(fixedClock)), eq(250))).thenReturn(0);

        scheduler.processExpiredReservations();

        verify(reservationService).expireDue(Instant.now(fixedClock), 250);
    }
}
