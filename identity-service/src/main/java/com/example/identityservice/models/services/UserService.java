package com.example.identityservice.models.services;

import com.example.identityservice.models.dto.req.UpdateProfileReq;
import com.example.identityservice.models.dto.res.UserProfileRes;

import java.util.UUID;

public interface UserService {

    UserProfileRes getProfile(UUID userId);

    UserProfileRes updateProfile(UUID userId, UpdateProfileReq req);

    com.example.identityservice.models.dto.res.InternalUserRes getInternalUser(UUID userId);
}
