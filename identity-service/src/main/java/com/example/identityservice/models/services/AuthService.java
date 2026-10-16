package com.example.identityservice.models.services;

import com.example.identityservice.models.dto.req.*;
import com.example.identityservice.models.dto.res.JwtRes;
import com.example.identityservice.models.dto.res.TokenResponseDTO;

import java.util.UUID;

public interface AuthService {

    void register(RegisterReq req);

    void verifyEmail(VerifyEmailReq req);

    void resendVerification(ResendVerificationReq req);

    JwtRes login(LoginReq req);

    TokenResponseDTO refreshToken(RefreshTokenReq req);

    void logout(String accessToken, String refreshToken);

    default void logout(String accessToken) {
        logout(accessToken, null);
    }

    void forgotPassword(ForgotPasswordReq req);

    void resetPassword(ResetPasswordReq req);

    void changePassword(UUID userId, ChangePasswordReq req);

    void revokeAllSessions(UUID userId);
}
