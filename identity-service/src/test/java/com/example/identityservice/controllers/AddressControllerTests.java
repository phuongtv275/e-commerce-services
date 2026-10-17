package com.example.identityservice.controllers;

import com.ecommerce.common.constant.ErrorCode;
import com.example.identityservice.models.constants.RoleName;
import com.example.identityservice.models.constants.UserStatus;
import com.example.identityservice.models.dto.req.AddressReq;
import com.example.identityservice.models.dto.req.UpdateProfileReq;
import com.example.identityservice.models.entities.Address;
import com.example.identityservice.models.entities.Role;
import com.example.identityservice.models.entities.User;
import com.example.identityservice.models.repositories.AddressRepository;
import com.example.identityservice.models.repositories.RoleRepository;
import com.example.identityservice.models.repositories.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AddressControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AddressRepository addressRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User userA;
    private User userB;

    @BeforeEach
    void setUp() {
        addressRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role customerRole = roleRepository.save(Role.builder()
                .roleName(RoleName.ROLE_CUSTOMER)
                .description("Customer")
                .build());

        userA = userRepository.save(User.builder()
                .email("usera@example.com")
                .fullName("User A")
                .phoneNumber("0912345678")
                .passwordHash(passwordEncoder.encode("Password123@"))
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .roles(Set.of(customerRole))
                .build());

        userB = userRepository.save(User.builder()
                .email("userb@example.com")
                .fullName("User B")
                .phoneNumber("0987654321")
                .passwordHash(passwordEncoder.encode("Password123@"))
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .roles(Set.of(customerRole))
                .build());
    }

    @Test
    @DisplayName("POS-10: User A creates, reads, updates, and deletes their own address")
    void testAddressCrudSelf() throws Exception {
        AddressReq req = new AddressReq("User A Home", "0912345678", "123 Street", "Apt 4", "District 1", "Hanoi", "Vietnam", true);

        // 1. Create Address
        String responseStr = mockMvc.perform(post("/api/v1/users/me/addresses")
                        .with(user(userA.getId().toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.recipientName").value("User A Home"))
                .andExpect(jsonPath("$.data.default").value(true))
                .andReturn().getResponse().getContentAsString();

        String addressIdStr = objectMapper.readTree(responseStr).get("data").get("id").asText();
        UUID addressId = UUID.fromString(addressIdStr);

        // 2. Get User A's addresses
        mockMvc.perform(get("/api/v1/users/me/addresses")
                        .with(user(userA.getId().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(addressId.toString()));

        // 3. Update Address
        AddressReq updateReq = new AddressReq("User A Office", "0912345678", "456 Office Blvd", null, "District 2", "Hanoi", "Vietnam", false);
        mockMvc.perform(put("/api/v1/users/me/addresses/" + addressId)
                        .with(user(userA.getId().toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recipientName").value("User A Office"));

        // 4. Delete Address
        mockMvc.perform(delete("/api/v1/users/me/addresses/" + addressId)
                        .with(user(userA.getId().toString())))
                .andExpect(status().isOk());

        assertThat(addressRepository.findByIdAndUserIdAndDeletedAtIsNull(addressId, userA.getId())).isEmpty();
    }

    @Test
    @DisplayName("NEG-07: Anti-BOLA: User A cannot read, update, or delete User B's address")
    void testAntiBolaProtection() throws Exception {
        Address addressB = addressRepository.save(Address.builder()
                .user(userB)
                .recipientName("User B Secret Address")
                .phone("0987654321")
                .addressLine1("789 Secret Rd")
                .district("District 3")
                .city("HCM")
                .country("Vietnam")
                .isDefault(true)
                .build());

        // User A tries to GET User B's address -> 404 Resource Not Found
        mockMvc.perform(get("/api/v1/users/me/addresses/" + addressB.getId())
                        .with(user(userA.getId().toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.RESOURCE_NOT_FOUND.name()));

        // User A tries to PUT User B's address -> 404
        AddressReq updateReq = new AddressReq("Hacked", "0911111111", "Hacked Rd", null, "Dist", "City", "Vietnam", false);
        mockMvc.perform(put("/api/v1/users/me/addresses/" + addressB.getId())
                        .with(user(userA.getId().toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isNotFound());

        // User A tries to DELETE User B's address -> 404
        mockMvc.perform(delete("/api/v1/users/me/addresses/" + addressB.getId())
                        .with(user(userA.getId().toString())))
                .andExpect(status().isNotFound());

        // Address B should still exist and not be modified
        Address intactAddress = addressRepository.findById(addressB.getId()).orElseThrow();
        assertThat(intactAddress.getDeletedAt()).isNull();
        assertThat(intactAddress.getRecipientName()).isEqualTo("User B Secret Address");
    }

    @Test
    @DisplayName("Profile: User can view and update their profile")
    void testProfileViewAndUpdate() throws Exception {
        // View profile
        mockMvc.perform(get("/api/v1/users/me")
                        .with(user(userA.getId().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("usera@example.com"))
                .andExpect(jsonPath("$.data.fullName").value("User A"));

        // Update profile
        UpdateProfileReq updateReq = new UpdateProfileReq("User A Updated", "0988888888");
        mockMvc.perform(patch("/api/v1/users/me")
                        .with(user(userA.getId().toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName").value("User A Updated"))
                .andExpect(jsonPath("$.data.phoneNumber").value("0988888888"));

        User updatedUser = userRepository.findById(userA.getId()).orElseThrow();
        assertThat(updatedUser.getFullName()).isEqualTo("User A Updated");
    }

    @Test
    @DisplayName("POS-11: Set default address and update address to default cleanly rotates default status")
    void testSetDefaultAddressAndRotate() throws Exception {
        // Address 1 (Default)
        Address addr1 = addressRepository.save(Address.builder()
                .user(userA)
                .recipientName("Address 1")
                .phone("0912345678")
                .addressLine1("111 First Ave")
                .city("Hanoi")
                .country("Vietnam")
                .isDefault(true)
                .build());

        // Address 2 (Non-default)
        Address addr2 = addressRepository.save(Address.builder()
                .user(userA)
                .recipientName("Address 2")
                .phone("0912345678")
                .addressLine1("222 Second Ave")
                .city("Hanoi")
                .country("Vietnam")
                .isDefault(false)
                .build());

        // 1. PATCH /api/v1/users/me/addresses/{addr2}/default
        mockMvc.perform(patch("/api/v1/users/me/addresses/" + addr2.getId() + "/default")
                        .with(user(userA.getId().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.default").value(true));

        Address updatedAddr1 = addressRepository.findById(addr1.getId()).orElseThrow();
        Address updatedAddr2 = addressRepository.findById(addr2.getId()).orElseThrow();
        assertThat(updatedAddr2.isDefault()).isTrue();
        assertThat(updatedAddr1.isDefault()).isFalse();

        // 2. PUT /api/v1/users/me/addresses/{addr1} with isDefault = true
        AddressReq req = new AddressReq("Address 1 Updated", "0912345678", "111 First Ave New", null, "Dist 1", "Hanoi", "Vietnam", true);
        mockMvc.perform(put("/api/v1/users/me/addresses/" + addr1.getId())
                        .with(user(userA.getId().toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.default").value(true));

        updatedAddr1 = addressRepository.findById(addr1.getId()).orElseThrow();
        updatedAddr2 = addressRepository.findById(addr2.getId()).orElseThrow();
        assertThat(updatedAddr1.isDefault()).isTrue();
        assertThat(updatedAddr2.isDefault()).isFalse();
    }
}
