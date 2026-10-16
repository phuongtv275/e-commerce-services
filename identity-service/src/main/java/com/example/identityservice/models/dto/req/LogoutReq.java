package com.example.identityservice.models.dto.req;

public record LogoutReq(
        String token,
        String refreshToken
) {
    public LogoutReq(String token) {
        this(token, null);
    }
}
