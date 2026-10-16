package com.example.identityservice.models.dto.res;

import java.util.List;

public record TokenResponseDTO(
        String accessToken,
        String refreshToken,
        String tokenType,
        String type,
        List<String> roles
) {
    public TokenResponseDTO(String accessToken, String refreshToken, List<String> roles) {
        this(accessToken, refreshToken, "Bearer", "Bearer", roles);
    }

    public TokenResponseDTO(String accessToken, String refreshToken) {
        this(accessToken, refreshToken, "Bearer", "Bearer", List.of());
    }
}
