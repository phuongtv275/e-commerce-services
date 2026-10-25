package com.example.inventoryservice.controller;

import com.ecommerce.common.response.ApiResponse;
import com.example.inventoryservice.dto.CreateReservationRequest;
import com.example.inventoryservice.dto.ReleaseReservationRequest;
import com.example.inventoryservice.dto.ReservationResponse;
import com.example.inventoryservice.service.ReservationItemCommand;
import com.example.inventoryservice.service.ReservationResult;
import com.example.inventoryservice.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/inventory/reservations")
@RequiredArgsConstructor
public class InternalReservationController {

    private final ReservationService reservationService;

    @PostMapping
    @PreAuthorize("hasRole('SERVICE_ORDER')")
    public ResponseEntity<ApiResponse<ReservationResponse>> createReservation(
            @Valid @RequestBody CreateReservationRequest request) {

        String correlationId = MDC.get("correlationId");
        List<ReservationItemCommand> commands = request.items().stream()
                .map(i -> new ReservationItemCommand(i.skuId(), i.quantity()))
                .toList();

        ReservationResult result = reservationService.reserve(request.orderId(), commands, correlationId);
        ReservationResponse response = ReservationResponse.from(result);

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @PostMapping("/{orderId}/release")
    @PreAuthorize("hasRole('SERVICE_ORDER')")
    public ResponseEntity<ApiResponse<ReservationResponse>> releaseReservation(
            @PathVariable UUID orderId,
            @RequestBody(required = false) ReleaseReservationRequest request) {

        String correlationId = MDC.get("correlationId");
        String reason = (request != null) ? request.reason() : null;

        ReservationResult result = reservationService.release(orderId, reason, correlationId);
        ReservationResponse response = ReservationResponse.from(result);

        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
