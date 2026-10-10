
package com.resq.config;

import com.resq.service.JwtService;
import io.jsonwebtoken.JwtException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

@Component
public class JwtAuthInterceptor implements HandlerInterceptor {

    private final JwtService jwtService;

    public JwtAuthInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) throws IOException {

        // 1. 从 HTTP 请求头获取 Token
        String authorization =
                request.getHeader("Authorization");

        if (authorization == null ||
                !authorization.startsWith("Bearer ")) {
            response.sendError(401, "Unauthorized");
            return false;
        }

        String token = authorization.substring(7).trim();

        // 2. 使用 JwtService 验证 Token
        try {
            Long userId = jwtService.parseToken(token);

            // 3. 保存已经验证的用户 ID
            request.setAttribute("currentUserId", userId);

            // 4. 允许进入 Controller
            return true;

        } catch (JwtException | IllegalArgumentException e) {
            response.sendError(401, "Invalid token");
            return false;
        }
    }
}
