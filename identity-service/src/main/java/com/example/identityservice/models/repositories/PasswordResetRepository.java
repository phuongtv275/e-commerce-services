package com.example.identityservice.models.repositories;

import com.example.identityservice.models.entities.PasswordReset;
import com.example.identityservice.models.entities.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PasswordResetRepository extends JpaRepository<PasswordReset, UUID> {

    Optional<PasswordReset> findByTokenHash(String tokenHash);

    @Modifying
    @Transactional
    @Query("UPDATE PasswordReset p SET p.used = true, p.usedAt = :now WHERE p.tokenHash = :tokenHash AND p.used = false AND p.expiresAt > :now")
    int consumeResetTokenAtomic(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    default int consumeResetTokenAtomic(String tokenHash) {
        return consumeResetTokenAtomic(tokenHash, Instant.now());
    }

    Optional<PasswordReset> findByUser(User user);

    @Modifying
    @Transactional
    @Query("DELETE FROM PasswordReset p WHERE p.used = true OR p.expiresAt < :cutoff")
    int deleteExpiredOrUsedResets(@Param("cutoff") Instant cutoff);
}
