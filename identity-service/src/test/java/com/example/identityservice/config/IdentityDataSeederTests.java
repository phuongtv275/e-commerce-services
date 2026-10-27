package com.example.identityservice.config;

import com.example.identityservice.models.constants.RoleName;
import com.example.identityservice.models.constants.UserStatus;
import com.example.identityservice.models.entities.Role;
import com.example.identityservice.models.entities.User;
import com.example.identityservice.models.repositories.RoleRepository;
import com.example.identityservice.models.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdentityDataSeederTests {

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private IdentityDataSeeder seeder;

    @BeforeEach
    void setUp() {
        seeder = new IdentityDataSeeder(roleRepository, userRepository, passwordEncoder);
    }

    @Test
    @DisplayName("Positive: Should seed 6 roles and admin user when database is empty")
    void shouldSeedRolesAndAdminWhenEmpty() throws Exception {
        when(roleRepository.findByRoleName(any(RoleName.class))).thenReturn(Optional.empty());
        when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(userRepository.findByEmailAndDeletedAtIsNull("admin@ecom.local")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$12$Cy9GaGBoOzeE9axBnnbHKe7mvwXWjpLq8jnHUyGtGEcOWhoTKFisC");

        seeder.run(null);

        // Verify 6 roles were saved
        verify(roleRepository, times(6)).save(any(Role.class));
        for (RoleName roleName : RoleName.values()) {
            verify(roleRepository).findByRoleName(roleName);
        }

        // Verify admin was saved with correct attributes
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User savedAdmin = userCaptor.getValue();

        assertThat(savedAdmin.getEmail()).isEqualTo("admin@ecom.local");
        assertThat(savedAdmin.getFullName()).isEqualTo("System Administrator");
        assertThat(savedAdmin.getPhoneNumber()).isEqualTo("0900000001");
        assertThat(savedAdmin.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(savedAdmin.isEmailVerified()).isTrue();
        assertThat(savedAdmin.getPasswordHash()).isNotEmpty();
        assertThat(savedAdmin.getRoles()).isNotEmpty();
        assertThat(savedAdmin.getRoles().iterator().next().getRoleName()).isEqualTo(RoleName.ROLE_ADMINISTRATOR);
    }

    @Test
    @DisplayName("Positive: Should be idempotent and not re-seed when data already exists")
    void shouldBeIdempotentWhenDataAlreadyExists() throws Exception {
        Role adminRole = Role.builder().roleName(RoleName.ROLE_ADMINISTRATOR).build();
        when(roleRepository.findByRoleName(any(RoleName.class))).thenAnswer(invocation -> {
            RoleName name = invocation.getArgument(0);
            return Optional.of(Role.builder().roleName(name).build());
        });

        User existingAdmin = User.builder()
                .email("admin@ecom.local")
                .fullName("System Administrator")
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .roles(new HashSet<>(Set.of(adminRole)))
                .build();
        when(userRepository.findByEmailAndDeletedAtIsNull("admin@ecom.local")).thenReturn(Optional.of(existingAdmin));

        seeder.run(null);

        verify(roleRepository, never()).save(any(Role.class));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Negative/Edge: Should assign ROLE_ADMINISTRATOR if admin exists without the role")
    void shouldAssignAdminRoleIfAdminMissingRole() throws Exception {
        Role adminRole = Role.builder().roleName(RoleName.ROLE_ADMINISTRATOR).build();
        when(roleRepository.findByRoleName(RoleName.ROLE_ADMINISTRATOR)).thenReturn(Optional.of(adminRole));
        when(roleRepository.findByRoleName(argThat(rn -> rn != RoleName.ROLE_ADMINISTRATOR)))
                .thenAnswer(inv -> Optional.of(Role.builder().roleName(inv.getArgument(0)).build()));

        User existingAdminWithoutRole = User.builder()
                .email("admin@ecom.local")
                .roles(new HashSet<>())
                .build();
        when(userRepository.findByEmailAndDeletedAtIsNull("admin@ecom.local")).thenReturn(Optional.of(existingAdminWithoutRole));

        seeder.run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getRoles()).contains(adminRole);
    }
}
