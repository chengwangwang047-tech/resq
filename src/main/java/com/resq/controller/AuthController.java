
package com.resq.controller;

import com.resq.dto.UserRegisterDTO;
import com.resq.dto.UserLoginDTO;
import com.resq.service.AuthService;
import com.resq.service.JwtService;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;

    // 构造器注入
    public AuthController(AuthService authService,
                          JwtService jwtService) {
        this.authService = authService;
        this.jwtService = jwtService;
    }

    // 用户注册
    @PostMapping("/register")
    public ResponseEntity<Map<String, Long>> register(
            @Valid @RequestBody UserRegisterDTO request) {

        Long userId = authService.register(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(Map.of("userId", userId));
    }

    // 用户登录
    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(
            @Valid @RequestBody UserLoginDTO request) {

        // 1. 验证用户名和密码
        Long userId = authService.authenticate(request);

        // 2. 验证成功，签发 JWT
        String token = jwtService.generateToken(userId);

        // 3. 返回 JWT
        return ResponseEntity.ok(
                Map.of(
                        "token", token,
                        "tokenType", "Bearer"
                )
        );
    }
}
