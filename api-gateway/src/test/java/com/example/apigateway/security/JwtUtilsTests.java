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
    @DisplayName("parseToken with raw string secret should succeed")
    void testParseTokenWithRawStringSecret() {
        JwtProperties properties = new JwtProperties();
        properties.setSecretKey(RAW_STRING_SECRET);
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
}
