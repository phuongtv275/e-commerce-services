package com.example.identityservice.models.repositories;

import com.example.identityservice.models.constants.RoleName;
import com.example.identityservice.models.constants.UserStatus;
import com.example.identityservice.models.entities.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@org.springframework.boot.persistence.autoconfigure.EntityScan(basePackages = {"com.example.identityservice.models.entities", "com.ecommerce.common.outbox"})
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class RepositoryLayerTests {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordResetRepository passwordResetRepository;

    @Autowired
    private AddressRepository addressRepository;

    @Test
    @DisplayName("UserRepository: findWithRolesByEmail should load User and Roles in one graph")
    void testFindWithRolesByEmail() {
        Role role = Role.builder().roleName(RoleName.ROLE_CUSTOMER).description("Customer").build();
        roleRepository.save(role);

        User user = User.builder()
                .email("test@example.com")
                .passwordHash("hashed_password")
                .fullName("Test User")
                .phoneNumber("0912345678")
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .roles(Set.of(role))
                .build();
        userRepository.save(user);

        entityManager.flush();
        entityManager.clear();

        Optional<User> found = userRepository.findWithRolesByEmail("test@example.com");
        assertThat(found).isPresent();
        assertThat(found.get().getEmail()).isEqualTo("test@example.com");
        assertThat(found.get().getRoles()).hasSize(1);
        assertThat(found.get().getRoles().iterator().next().getRoleName()).isEqualTo(RoleName.ROLE_CUSTOMER);
    }

    @Test
    @DisplayName("PasswordResetRepository: consumeResetTokenAtomic should return 1 on first use, 0 on replay")
    void testConsumeResetTokenAtomic() {
        User user = User.builder()
                .email("reset@example.com")
                .passwordHash("hashed_pass")
                .fullName("Reset User")
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .build();
        user = userRepository.save(user);

        String tokenHash = "dummy_sha256_hash_12345";
        PasswordReset reset = PasswordReset.builder()
                .user(user)
                .tokenHash(tokenHash)
                .expiresAt(Instant.now().plusSeconds(900))
                .used(false)
                .build();
        passwordResetRepository.save(reset);
        entityManager.flush();

        // First attempt: should succeed and return 1
        int updated = passwordResetRepository.consumeResetTokenAtomic(tokenHash);
        assertThat(updated).isEqualTo(1);

        // Second attempt with same token (replay attack): should return 0
        int replayed = passwordResetRepository.consumeResetTokenAtomic(tokenHash);
        assertThat(replayed).isEqualTo(0);
    }

    @Test
    @DisplayName("RefreshTokenRepository: revokeEntireFamily should mark all tokens with familyId as revoked")
    void testRevokeEntireFamily() {
        User user = User.builder()
                .email("token@example.com")
                .passwordHash("hashed_pass")
                .fullName("Token User")
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .build();
        user = userRepository.save(user);

        UUID familyId = UUID.randomUUID();
        RefreshToken t1 = RefreshToken.builder()
                .user(user)
                .tokenHash("hash_1")
                .familyId(familyId)
                .expiresAt(Instant.now().plusSeconds(3600))
                .revoked(false)
                .build();
        RefreshToken t2 = RefreshToken.builder()
                .user(user)
                .tokenHash("hash_2")
                .familyId(familyId)
                .expiresAt(Instant.now().plusSeconds(3600))
                .revoked(false)
                .build();
        refreshTokenRepository.save(t1);
        refreshTokenRepository.save(t2);
        entityManager.flush();

        int revokedCount = refreshTokenRepository.revokeEntireFamily(familyId);
        assertThat(revokedCount).isEqualTo(2);

        entityManager.clear();
        RefreshToken refreshedT1 = refreshTokenRepository.findByTokenHash("hash_1").orElseThrow();
        assertThat(refreshedT1.isRevoked()).isTrue();
    }

    @Test
    @DisplayName("AddressRepository: findByIdAndUserIdAndDeletedAtIsNull should prevent BOLA")
    void testAddressAntiBOLA() {
        User userA = User.builder().email("usera@example.com").passwordHash("h").fullName("User A").status(UserStatus.ACTIVE).emailVerified(true).build();
        User userB = User.builder().email("userb@example.com").passwordHash("h").fullName("User B").status(UserStatus.ACTIVE).emailVerified(true).build();
        userA = userRepository.save(userA);
        userB = userRepository.save(userB);

        Address addressOfB = Address.builder()
                .user(userB)
                .recipientName("Recipient B")
                .phone("0912345678")
                .addressLine1("123 Street")
                .city("Hanoi")
                .country("VN")
                .isDefault(true)
                .build();
        addressOfB = addressRepository.save(addressOfB);
        entityManager.flush();
        entityManager.clear();

        // User A attempts to find User B's address
        Optional<Address> unauthorized = addressRepository.findByIdAndUserIdAndDeletedAtIsNull(addressOfB.getId(), userA.getId());
        assertThat(unauthorized).isEmpty();

        // User B accesses their own address
        Optional<Address> authorized = addressRepository.findByIdAndUserIdAndDeletedAtIsNull(addressOfB.getId(), userB.getId());
        assertThat(authorized).isPresent();
    }
}
