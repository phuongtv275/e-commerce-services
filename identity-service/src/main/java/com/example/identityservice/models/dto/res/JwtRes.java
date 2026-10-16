package com.example.identityservice.models.dto.res;

import lombok.Builder;

import java.util.List;

@Builder
public record JwtRes(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        List<String> roles
) {
    public JwtRes(String accessToken, String refreshToken, List<String> roles) {
        this(accessToken, refreshToken, "Bearer", 900L, roles);
    }

    public JwtRes(String accessToken, String refreshToken, String type, List<String> roles) {
        this(accessToken, refreshToken, type != null ? type : "Bearer", 900L, roles);
    }

    public String type() {
        return tokenType;
    }
}
