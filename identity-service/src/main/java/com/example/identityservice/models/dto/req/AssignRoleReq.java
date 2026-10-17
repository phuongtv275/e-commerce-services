package com.example.identityservice.models.dto.req;

import jakarta.validation.constraints.NotBlank;

public record AssignRoleReq(
        @NotBlank(message = "Role name cannot be blank")
        String roleName
) {}
