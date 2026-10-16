package com.example.identityservice.models.repositories;

import com.example.identityservice.models.entities.RefreshToken;
import com.example.identityservice.models.entities.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RefreshToken r WHERE r.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashWithLock(@Param("tokenHash") String tokenHash);

    @Modifying
    @Transactional
    @Query("UPDATE RefreshToken r SET r.revoked = true WHERE r.familyId = :familyId")
    int revokeEntireFamily(@Param("familyId") UUID familyId);

    @Modifying
    @Transactional
    @Query("UPDATE RefreshToken r SET r.revoked = true WHERE r.user.id = :userId")
    int revokeAllUserSessions(@Param("userId") UUID userId);

    @Modifying
    @Transactional
    int deleteByUser(User user);

    // Backward compatibility aliases
    default Optional<RefreshToken> findByToken(String token) {
        return findByTokenHash(token);
    }

    default Optional<RefreshToken> findByTokenWithLock(String token) {
        return findByTokenHashWithLock(token);
    }
}
