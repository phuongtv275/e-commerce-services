package com.example.identityservice.models.services.impl;

import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.identityservice.models.dto.req.UpdateProfileReq;
import com.example.identityservice.models.dto.res.UserProfileRes;
import com.example.identityservice.models.entities.User;
import com.example.identityservice.models.repositories.UserRepository;
import com.example.identityservice.models.services.AuditLogService;
import com.example.identityservice.models.services.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    @Override
    @Transactional(readOnly = true)
    public UserProfileRes getProfile(UUID userId) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        List<String> roles = user.getRoles() != null
                ? user.getRoles().stream().map(r -> r.getRoleName().name()).toList()
                : List.of();

        return UserProfileRes.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .phoneNumber(user.getPhoneNumber())
                .status(user.getStatus())
                .emailVerified(user.isEmailVerified())
                .roles(roles)
                .createdAt(user.getCreatedAt())
                .build();
    }

    @Override
    @Transactional
    public UserProfileRes updateProfile(UUID userId, UpdateProfileReq req) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        if (req.fullName() != null && !req.fullName().isBlank()) {
            user.setFullName(req.fullName().trim());
        }
        if (req.phoneNumber() != null) {
            user.setPhoneNumber(req.phoneNumber().trim());
        }

        User saved = userRepository.save(user);

        auditLogService.logAction(
                userId,
                saved.getEmail(),
                "UPDATE_PROFILE",
                "USER",
                userId.toString(),
                "{\"fullName\":\"" + saved.getFullName() + "\"}",
                null,
                MDC.get("correlationId")
        );

        List<String> roles = saved.getRoles() != null
                ? saved.getRoles().stream().map(r -> r.getRoleName().name()).toList()
                : List.of();

        return UserProfileRes.builder()
                .id(saved.getId())
                .email(saved.getEmail())
                .fullName(saved.getFullName())
                .phoneNumber(saved.getPhoneNumber())
                .status(saved.getStatus())
                .emailVerified(saved.isEmailVerified())
                .roles(roles)
                .createdAt(saved.getCreatedAt())
                .build();
    }
}
