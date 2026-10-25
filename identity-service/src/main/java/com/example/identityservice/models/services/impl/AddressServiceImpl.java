package com.example.identityservice.models.services.impl;

import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.identityservice.models.dto.req.AddressReq;
import com.example.identityservice.models.dto.res.AddressRes;
import com.example.identityservice.models.dto.res.InternalAddressRes;
import com.example.identityservice.models.entities.Address;
import com.example.identityservice.models.entities.User;
import com.example.identityservice.models.repositories.AddressRepository;
import com.example.identityservice.models.repositories.UserRepository;
import com.example.identityservice.models.services.AddressService;
import com.example.identityservice.models.services.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AddressServiceImpl implements AddressService {

    private final AddressRepository addressRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    private AddressRes mapToRes(Address a) {
        return AddressRes.builder()
                .id(a.getId())
                .recipientName(a.getRecipientName())
                .phone(a.getPhone())
                .addressLine1(a.getAddressLine1())
                .addressLine2(a.getAddressLine2())
                .district(a.getDistrict())
                .city(a.getCity())
                .country(a.getCountry())
                .isDefault(a.isDefault())
                .createdAt(a.getCreatedAt())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AddressRes> getUserAddresses(UUID userId) {
        return addressRepository.findAllByUserIdAndDeletedAtIsNull(userId).stream()
                .map(this::mapToRes)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AddressRes getAddressById(UUID userId, UUID addressId) {
        Address address = addressRepository.findByIdAndUserIdAndDeletedAtIsNull(addressId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Address not found with id: " + addressId));
        return mapToRes(address);
    }

    @Override
    @Transactional(readOnly = true)
    public InternalAddressRes getInternalAddress(UUID userId, UUID addressId) {
        return addressRepository.findInternalAddressByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Address not found with id: " + addressId));
    }

    @Override
    @Transactional
    public AddressRes createAddress(UUID userId, AddressReq req) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));
        String userEmail = user.getEmail();

        boolean isDefault = Boolean.TRUE.equals(req.isDefault());
        List<Address> existing = addressRepository.findAllByUserIdAndDeletedAtIsNull(userId);
        if (existing.isEmpty()) {
            isDefault = true;
        }

        if (isDefault) {
            addressRepository.clearDefaultAddresses(userId);
            // Re-fetch managed user after persistence context was cleared
            user = userRepository.findByIdAndDeletedAtIsNull(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));
        }

        Address address = Address.builder()
                .user(user)
                .recipientName(req.recipientName().trim())
                .phone(req.phone().trim())
                .addressLine1(req.addressLine1().trim())
                .addressLine2(req.addressLine2() != null ? req.addressLine2().trim() : null)
                .district(req.district().trim())
                .city(req.city().trim())
                .country(req.country() != null && !req.country().isBlank() ? req.country().trim() : "Vietnam")
                .isDefault(isDefault)
                .build();

        Address saved = addressRepository.save(address);

        auditLogService.logAction(
                userId,
                userEmail,
                "CREATE_ADDRESS",
                "ADDRESS",
                saved.getId().toString(),
                "{\"isDefault\":" + isDefault + "}",
                null,
                MDC.get("correlationId")
        );

        return mapToRes(saved);
    }

    @Override
    @Transactional
    public AddressRes updateAddress(UUID userId, UUID addressId, AddressReq req) {
        Address address = addressRepository.findByIdAndUserIdAndDeletedAtIsNull(addressId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Address not found with id: " + addressId));

        String userEmail = (address.getUser() != null) ? address.getUser().getEmail() : null;

        if (Boolean.TRUE.equals(req.isDefault())) {
            addressRepository.clearDefaultAddresses(userId);
            // Re-fetch managed address after persistence context was cleared
            address = addressRepository.findByIdAndUserIdAndDeletedAtIsNull(addressId, userId)
                    .orElseThrow(() -> new ResourceNotFoundException("Address not found with id: " + addressId));
            address.setDefault(true);
        } else if (req.isDefault() != null) {
            address.setDefault(req.isDefault());
        }

        if (req.recipientName() != null && !req.recipientName().isBlank()) {
            address.setRecipientName(req.recipientName().trim());
        }
        if (req.phone() != null && !req.phone().isBlank()) {
            address.setPhone(req.phone().trim());
        }
        if (req.addressLine1() != null && !req.addressLine1().isBlank()) {
            address.setAddressLine1(req.addressLine1().trim());
        }
        if (req.addressLine2() != null) {
            address.setAddressLine2(req.addressLine2().trim());
        }
        if (req.district() != null && !req.district().isBlank()) {
            address.setDistrict(req.district().trim());
        }
        if (req.city() != null && !req.city().isBlank()) {
            address.setCity(req.city().trim());
        }
        if (req.country() != null && !req.country().isBlank()) {
            address.setCountry(req.country().trim());
        }

        Address saved = addressRepository.save(address);

        auditLogService.logAction(
                userId,
                userEmail,
                "UPDATE_ADDRESS",
                "ADDRESS",
                addressId.toString(),
                "{}",
                null,
                MDC.get("correlationId")
        );

        return mapToRes(saved);
    }

    @Override
    @Transactional
    public void deleteAddress(UUID userId, UUID addressId) {
        Address address = addressRepository.findByIdAndUserIdAndDeletedAtIsNull(addressId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Address not found with id: " + addressId));

        String userEmail = (address.getUser() != null) ? address.getUser().getEmail() : null;

        address.setDeletedAt(Instant.now());
        addressRepository.save(address);

        auditLogService.logAction(
                userId,
                userEmail,
                "DELETE_ADDRESS",
                "ADDRESS",
                addressId.toString(),
                "{}",
                null,
                MDC.get("correlationId")
        );
    }

    @Override
    @Transactional
    public AddressRes setDefaultAddress(UUID userId, UUID addressId) {
        Address address = addressRepository.findByIdAndUserIdAndDeletedAtIsNull(addressId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Address not found with id: " + addressId));

        String userEmail = (address.getUser() != null) ? address.getUser().getEmail() : null;

        addressRepository.clearDefaultAddresses(userId);
        // Re-fetch managed address after persistence context was cleared
        address = addressRepository.findByIdAndUserIdAndDeletedAtIsNull(addressId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Address not found with id: " + addressId));
        address.setDefault(true);
        Address saved = addressRepository.save(address);

        auditLogService.logAction(
                userId,
                userEmail,
                "SET_DEFAULT_ADDRESS",
                "ADDRESS",
                addressId.toString(),
                "{\"isDefault\":true}",
                null,
                MDC.get("correlationId")
        );

        return mapToRes(saved);
    }
}
