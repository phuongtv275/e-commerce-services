package com.example.identityservice.models.services;

import com.example.identityservice.models.constants.UserStatus;
import com.example.identityservice.models.dto.req.AssignRoleReq;
import com.example.identityservice.models.dto.res.UserSummaryRes;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface AdminService {

    Page<UserSummaryRes> getUsers(String email, UserStatus status, Pageable pageable);

    void assignRole(UUID userId, AssignRoleReq req);

    void revokeRole(UUID userId, String roleName);
}
