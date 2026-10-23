
package com.resq.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;

import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtAuthInterceptor jwtAuthInterceptor;

    // 构造器注入
    public WebMvcConfig(JwtAuthInterceptor jwtAuthInterceptor) {
        this.jwtAuthInterceptor = jwtAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {

        // 资源查询公开，所有资源写操作必须通过现有 JWT 验证。
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request,
                                     HttpServletResponse response,
                                     Object handler) throws IOException {
                return switch (request.getMethod()) {
                    case "GET", "HEAD", "OPTIONS" -> true;
                    default -> jwtAuthInterceptor.preHandle(request, response, handler);
                };
            }
        }).addPathPatterns("/api/resources", "/api/resources/**");

        registry.addInterceptor(jwtAuthInterceptor)
                .addPathPatterns(
                        "/api/users",
                        "/api/users/**",
                        "/api/reservations",
                        "/api/reservations/**",
                        "/api/slots/**");
    }
}
