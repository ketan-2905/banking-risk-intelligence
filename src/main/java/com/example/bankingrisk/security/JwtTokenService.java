package com.example.bankingrisk.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class JwtTokenService {

    private final SecretKey secretKey;
    private final long expiryMs;

    public JwtTokenService(
        @Value("${app.jwt.secret}") String secret,
        @Value("${app.jwt.expiry-ms:3600000}") long expiryMs) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiryMs = expiryMs;
    }

    public String generateToken(UUID userId, Set<String> roles) {
        return Jwts.builder()
            .subject(userId.toString())
            .claim("roles", new ArrayList<>(roles))
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + expiryMs))
            .signWith(secretKey)
            .compact();
    }

    public UserPrincipal parseToken(String token) {
        Claims claims = Jwts.parser()
            .verifyWith(secretKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();

        UUID userId = UUID.fromString(claims.getSubject());
        @SuppressWarnings("unchecked")
        List<String> roleList = claims.get("roles", List.class);
        Set<String> roles = (roleList == null) ? Set.of() : new HashSet<>(roleList);
        return new UserPrincipal(userId, roles);
    }
}
