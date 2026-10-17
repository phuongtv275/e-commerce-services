package com.example.apigateway.security;

import com.example.apigateway.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilsTests {

    private static final String BASE64_SECRET = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";
    private static final String RAW_STRING_SECRET = "my-super-secret-key-that-is-at-least-32-characters-long!";

    @Test
    @DisplayName("parseToken with Base64 secret should succeed")
    void testParseTokenWithBase64Secret() {
        JwtProperties properties = new JwtProperties();
        properties.setSecretKey(BASE64_SECRET);
        JwtUtils jwtUtils = new JwtUtils(properties);

        var key = Keys.hmacShaKeyFor(io.jsonwebtoken.io.Decoders.BASE64.decode(BASE64_SECRET));
        String token = Jwts.builder()
                .subject("alice")
                .id("jti-1")
                .claim("roles", List.of("ROLE_USER"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(key)
                .compact();

        Claims claims = jwtUtils.parseToken(token);
        assertEquals("alice", claims.getSubject());
        assertEquals("jti-1", claims.getId());
        assertTrue(jwtUtils.isTokenValid(token));
        assertEquals("jti-1", jwtUtils.extractJti(token));
    }

    @Test
    @DisplayName("parseToken with raw string secret should succeed when secretFormat is raw")
    void testParseTokenWithRawStringSecret() {
        JwtProperties properties = new JwtProperties();
        properties.setSecretKey(RAW_STRING_SECRET);
        properties.setSecretFormat("raw");
        JwtUtils jwtUtils = new JwtUtils(properties);

        var key = Keys.hmacShaKeyFor(RAW_STRING_SECRET.getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .subject("bob")
                .id("jti-2")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(key)
                .compact();

        Claims claims = jwtUtils.parseToken(token);
        assertEquals("bob", claims.getSubject());
        assertEquals("jti-2", claims.getId());
        assertTrue(jwtUtils.isTokenValid(token));
    }

    @Test
    @DisplayName("Invalid Base64 in default base64 mode should throw exception and not fallback silently")
    void testInvalidBase64ThrowsInBase64Mode() {
        JwtProperties properties = new JwtProperties();
        properties.setSecretKey("invalid-base64-character-that-is-at-least-32-chars!");
        JwtUtils jwtUtils = new JwtUtils(properties);

        assertThrows(Exception.class, () -> jwtUtils.parseToken("some.token"));
    }

    @Test
    @DisplayName("parseToken with invalid token should return false on isTokenValid")
    void testInvalidToken() {
        JwtProperties properties = new JwtProperties();
        properties.setSecretKey(BASE64_SECRET);
        JwtUtils jwtUtils = new JwtUtils(properties);

        assertFalse(jwtUtils.isTokenValid("invalid.jwt.token"));
    }

    @Test
    @DisplayName("parseToken with explicit raw: and base64: prefixes")
    void testParseTokenWithExplicitPrefixes() {
        JwtProperties rawProps = new JwtProperties();
        rawProps.setSecretKey("raw:" + RAW_STRING_SECRET);
        JwtUtils rawUtils = new JwtUtils(rawProps);

        var key = Keys.hmacShaKeyFor(RAW_STRING_SECRET.getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .subject("carol")
                .signWith(key)
                .compact();

        assertEquals("carol", rawUtils.parseToken(token).getSubject());

        JwtProperties b64Props = new JwtProperties();
        b64Props.setSecretKey("base64:" + BASE64_SECRET);
        JwtUtils b64Utils = new JwtUtils(b64Props);
        var b64Key = Keys.hmacShaKeyFor(io.jsonwebtoken.io.Decoders.BASE64.decode(BASE64_SECRET));
        String b64Token = Jwts.builder().subject("dave").signWith(b64Key).compact();
        assertEquals("dave", b64Utils.parseToken(b64Token).getSubject());
    }

    @Test
    @DisplayName("Secret key shorter than 32 bytes should throw IllegalArgumentException")
    void testShortSecretKeyThrowsException() {
        JwtProperties properties = new JwtProperties();
        properties.setSecretKey("raw:too-short");
        JwtUtils jwtUtils = new JwtUtils(properties);

        assertThrows(IllegalArgumentException.class, () -> jwtUtils.parseToken("some.token"));
    }

    @Test
    @DisplayName("Fallback to default secret when secretKey is null, blank, or literal '${JWT_SECRET}'")
    void testFallbackToDefaultSecret() {
        for (String secret : new String[]{null, "", "   ", "${JWT_SECRET}"}) {
            JwtProperties properties = new JwtProperties();
            properties.setSecretKey(secret);
            JwtUtils jwtUtils = new JwtUtils(properties);

            var key = Keys.hmacShaKeyFor(io.jsonwebtoken.io.Decoders.BASE64.decode(BASE64_SECRET));
            String token = Jwts.builder()
                    .subject("test-user")
                    .signWith(key)
                    .compact();

            assertEquals("test-user", jwtUtils.parseToken(token).getSubject());
        }
    }

    @Test
    @DisplayName("Support Base64URL encoded secret with - or _")
    void testBase64UrlSecret() {
        String base64UrlSecret = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970"
                .replace('+', '-')
                .replace('/', '_');

        JwtProperties properties = new JwtProperties();
        properties.setSecretKey(base64UrlSecret);
        JwtUtils jwtUtils = new JwtUtils(properties);

        var key = Keys.hmacShaKeyFor(io.jsonwebtoken.io.Decoders.BASE64URL.decode(base64UrlSecret));
        String token = Jwts.builder()
                .subject("base64url-user")
                .signWith(key)
                .compact();

        assertEquals("base64url-user", jwtUtils.parseToken(token).getSubject());
    }

    @Test
    @DisplayName("In production profile, fallback secret throws IllegalStateException")
    void testProductionProfileRejectsFallbackSecret() {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setActiveProfiles("prod");

        JwtProperties properties = new JwtProperties();
        properties.setSecretKey(null);
        JwtUtils jwtUtils = new JwtUtils(properties, env);

        assertThrows(IllegalStateException.class, jwtUtils::validateSecretOnStartup);
    }
}
