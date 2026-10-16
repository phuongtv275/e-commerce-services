package com.example.identityservice.models.services;

import io.jsonwebtoken.Claims;

public interface RedisBlacklistService {

    /**
     * Ghi token vào danh sách đen (Blacklist) trong Redis.
     * Trích xuất jti và exp, tính TTL và lưu key "blacklist:{jti}" với giá trị "revoked".
     *
     * @param token chuỗi Access Token cần thu hồi
     * @return Claims giải mã từ token (dùng tiếp cho các tác vụ dọn dẹp)
     */
    Claims blacklistToken(String token);

    /**
     * Kiểm tra xem một jti (JWT ID) có nằm trong danh sách đen hay không.
     *
     * @param jti JWT ID cần kiểm tra
     * @return true nếu đã bị thu hồi/nằm trong blacklist, ngược lại false
     */
    boolean isBlacklisted(String jti);
}
