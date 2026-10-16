package com.example.apigateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GatewayAssertionSignerTests {

    private KeyPair keyPair;
    private String privateKeyPem;
    private GatewayAssertionSigner signer;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        this.keyPair = generator.generateKeyPair();

        String base64Key = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        this.privateKeyPem = "-----BEGIN PRIVATE KEY-----\n" + base64Key + "\n-----END PRIVATE KEY-----";
        this.signer = new GatewayAssertionSigner(privateKeyPem);
    }

    @Test
    @DisplayName("Should sign RSA assertion token with target audience and correct claims")
    void shouldSignRsaAssertionToken() {
        String userId = "user-12345";
        List<String> roles = List.of("ROLE_CUSTOMER", "ROLE_ADMINISTRATOR");
        String targetService = "order-service";

        String assertionToken = signer.signAssertion(userId, roles, targetService);
        assertNotNull(assertionToken);
        assertFalse(assertionToken.isBlank());

        // Verify with the corresponding Public Key (as TrustedHeaderAuthenticationFilter does)
        Claims claims = Jwts.parser()
                .verifyWith(keyPair.getPublic())
                .build()
                .parseSignedClaims(assertionToken)
                .getPayload();

        assertEquals("api-gateway", claims.getIssuer());
        assertEquals(userId, claims.getSubject());
        assertEquals(Set.of("order-service"), claims.getAudience());
        assertNotNull(claims.getId()); // jti
        assertNotNull(claims.getExpiration());
        assertTrue(claims.getExpiration().getTime() > System.currentTimeMillis());

        @SuppressWarnings("unchecked")
        List<String> parsedRoles = claims.get("roles", List.class);
        assertEquals(roles, parsedRoles);
    }

    @Test
    @DisplayName("Should throw IllegalStateException on startup if private key is missing or invalid")
    void shouldThrowExceptionOnMissingOrInvalidPrivateKey() {
        assertThrows(IllegalStateException.class, () -> new GatewayAssertionSigner(""));
        assertThrows(IllegalStateException.class, () -> new GatewayAssertionSigner(null));
        assertThrows(IllegalStateException.class, () -> new GatewayAssertionSigner("not-a-valid-key"));
    }

    @Test
    @DisplayName("Should reject RSA keys shorter than 2048 bits")
    void shouldRejectRsaKeysShorterThan2048Bits() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(1024);
        KeyPair weakPair = gen.generateKeyPair();
        String weakPem = "-----BEGIN PRIVATE KEY-----\n" +
                Base64.getEncoder().encodeToString(weakPair.getPrivate().getEncoded()) +
                "\n-----END PRIVATE KEY-----";

        assertThrows(IllegalStateException.class, () -> new GatewayAssertionSigner(weakPem));
    }
}
