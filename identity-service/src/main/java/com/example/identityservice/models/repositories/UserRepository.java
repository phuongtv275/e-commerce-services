package com.example.identityservice.models.repositories;

import com.example.identityservice.models.entities.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmailAndDeletedAtIsNull(String email);

    @EntityGraph(attributePaths = {"roles"})
    @Query("SELECT u FROM User u WHERE u.email = :email AND u.deletedAt IS NULL")
    Optional<User> findWithRolesByEmail(@Param("email") String email);

    boolean existsByEmailAndDeletedAtIsNull(String email);

    Optional<User> findByIdAndDeletedAtIsNull(UUID id);

    Optional<User> findByVerificationTokenHashAndDeletedAtIsNull(String verificationTokenHash);

    default Optional<User> findByUsername(String username) {
        return findByEmailAndDeletedAtIsNull(username);
    }
}
