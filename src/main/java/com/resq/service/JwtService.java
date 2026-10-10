
package com.resq.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationSeconds;

    public JwtService(
            @Value("${resq.jwt.secret-base64}") String secretBase64,
            @Value("${resq.jwt.expiration-seconds:3600}") long expirationSeconds) {

        this.key = Keys.hmacShaKeyFor(
                Decoders.BASE64.decode(secretBase64)
        );
        this.expirationSeconds = expirationSeconds;

        if (expirationSeconds <= 0) {
            throw new IllegalArgumentException("JWT 有效期必须大于 0");
        }
    }

    // 签发 JWT
    public String generateToken(Long userId) {

        Instant now = Instant.now();

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(Date.from(now))
                .expiration(Date.from(
                        now.plusSeconds(expirationSeconds)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    // 验证 JWT 并提取用户 ID
    public Long parseToken(String token) {

        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        try {
            return Long.parseLong(claims.getSubject());
        } catch (NumberFormatException e) {
            throw new JwtException("JWT 用户 ID 不合法", e);
        }
    }
}
