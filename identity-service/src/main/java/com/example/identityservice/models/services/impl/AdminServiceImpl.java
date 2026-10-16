package com.example.identityservice.models.services.impl;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.identityservice.models.constants.RoleName;
import com.example.identityservice.models.constants.UserStatus;
import com.example.identityservice.models.dto.req.AssignRoleReq;
import com.example.identityservice.models.dto.res.UserSummaryRes;
import com.example.identityservice.models.entities.Role;
import com.example.identityservice.models.entities.User;
import com.example.identityservice.models.repositories.RoleRepository;
import com.example.identityservice.models.repositories.UserRepository;
import com.example.identityservice.models.services.AdminService;
import com.example.identityservice.models.services.AuditLogService;
import com.example.identityservice.security.SecurityUtils;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final AuditLogService auditLogService;

    @Override
    @Transactional(readOnly = true)
    public Page<UserSummaryRes> getUsers(String email, UserStatus status, Pageable pageable) {
        Specification<User> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isNull(root.get("deletedAt")));

            if (email != null && !email.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("email")), "%" + email.trim().toLowerCase() + "%"));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return userRepository.findAll(spec, pageable).map(u -> {
            List<String> roles = u.getRoles() != null
                    ? u.getRoles().stream().map(r -> r.getRoleName().name()).toList()
                    : List.of();
            return UserSummaryRes.builder()
                    .id(u.getId())
                    .email(u.getEmail())
                    .fullName(u.getFullName())
                    .status(u.getStatus())
                    .roles(roles)
                    .createdAt(u.getCreatedAt())
                    .build();
        });
    }

    @Override
    @Transactional
    public void assignRole(UUID userId, AssignRoleReq req) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        String normalizedRoleName = req.roleName().trim().toUpperCase();
        if (!normalizedRoleName.startsWith("ROLE_")) {
            normalizedRoleName = "ROLE_" + normalizedRoleName;
        }

        RoleName rn;
        try {
            rn = RoleName.valueOf(normalizedRoleName);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Invalid role name: " + req.roleName());
        }

        Role role = roleRepository.findByRoleName(rn)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found with name: " + req.roleName()));

        Set<Role> roles = user.getRoles() != null ? new HashSet<>(user.getRoles()) : new HashSet<>();
        roles.add(role);
        user.setRoles(roles);
        userRepository.save(user);

        UUID adminId = SecurityUtils.getCurrentUserId();
        String adminEmail = SecurityUtils.getCurrentUserEmail();
        auditLogService.logAction(
                adminId,
                adminEmail,
                "ROLE_ASSIGNED",
                "USER",
                userId.toString(),
                "{\"role\":\"" + rn.name() + "\"}",
                null,
                MDC.get("correlationId")
        );
    }

    @Override
    @Transactional
    public void revokeRole(UUID userId, String roleName) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        String normalizedRoleName = roleName.trim().toUpperCase();
        if (!normalizedRoleName.startsWith("ROLE_")) {
            normalizedRoleName = "ROLE_" + normalizedRoleName;
        }

        RoleName rn;
        try {
            rn = RoleName.valueOf(normalizedRoleName);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Invalid role name: " + roleName);
        }

        Set<Role> roles = user.getRoles() != null ? new HashSet<>(user.getRoles()) : new HashSet<>();
        boolean removed = roles.removeIf(r -> r.getRoleName() == rn);
        if (removed) {
            user.setRoles(roles);
            userRepository.save(user);

            UUID adminId = SecurityUtils.getCurrentUserId();
            String adminEmail = SecurityUtils.getCurrentUserEmail();
            auditLogService.logAction(
                    adminId,
                    adminEmail,
                    "ROLE_REVOKED",
                    "USER",
                    userId.toString(),
                    "{\"role\":\"" + rn.name() + "\"}",
                    null,
                    MDC.get("correlationId")
            );
        }
    }
}
