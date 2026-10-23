package com.resq.controller;

import com.resq.config.JwtAuthInterceptor;
import com.resq.config.WebMvcConfig;
import com.resq.domain.ComputeResource;
import com.resq.dto.ResourceCreateDTO;
import com.resq.service.JwtService;
import com.resq.service.ResourceService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC registration and JWT verification; no Boot context or external services. */
@SpringJUnitConfig(ResourceAuthorizationTest.MvcConfiguration.class)
@WebAppConfiguration
class ResourceAuthorizationTest {
    @Configuration
    @EnableWebMvc
    @Import({WebMvcConfig.class, JwtAuthInterceptor.class, ResourceController.class,
            ProtectedProbeController.class})
    static class MvcConfiguration {
        @Bean ResourceService resourceService() { return mock(ResourceService.class); }
        @Bean SecretKey signingKey() {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            return Keys.hmacShaKeyFor(bytes);
        }
        @Bean JwtService jwtService(SecretKey signingKey) {
            return new JwtService(Base64.getEncoder().encodeToString(signingKey.getEncoded()), 3600);
        }
    }

    @RestController
    static class ProtectedProbeController {
        @GetMapping({"/api/users/me", "/api/reservations/me", "/api/slots/1/waitlist"})
        Long currentUser(@RequestAttribute("currentUserId") Long userId) { return userId; }
    }

    @Autowired private WebApplicationContext context;
    @Autowired private ResourceService resources;
    @Autowired private JwtService jwt;
    @Autowired private SecretKey signingKey;
    private MockMvc mvc;
    private static final String BODY = "{\"name\":\"test-resource\",\"type\":\"GPU\"}";

    @BeforeEach void setUp() {
        reset(resources);
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        when(resources.create(any(ResourceCreateDTO.class))).thenReturn(resource());
    }

    @Test void anonymousCreateIsRejectedBeforeService() throws Exception {
        mvc.perform(post("/api/resources").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(resources);
    }

    @Test void invalidTokenCannotCreateResource() throws Exception {
        mvc.perform(post("/api/resources").header(HttpHeaders.AUTHORIZATION, "Bearer malformed")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(resources);
    }

    @Test void expiredTokenCannotCreateResource() throws Exception {
        String expired = Jwts.builder().subject("7")
                .expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(signingKey, Jwts.SIG.HS256).compact();
        mvc.perform(post("/api/resources").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(resources);
    }

    @Test void authenticatedCreateReachesService() throws Exception {
        mvc.perform(post("/api/resources").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.generateToken(7L))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated()).andExpect(jsonPath("id").value(1));
        verify(resources).create(any(ResourceCreateDTO.class));
    }

    @Test void publicResourceReadsRemainAvailable() throws Exception {
        when(resources.findAll()).thenReturn(List.of(resource()));
        when(resources.findById(1L)).thenReturn(resource());
        when(resources.findSlotsByResourceId(1L, null)).thenReturn(List.of());
        mvc.perform(get("/api/resources")).andExpect(status().isOk());
        mvc.perform(get("/api/resources/1")).andExpect(status().isOk());
        mvc.perform(get("/api/resources/1/slots")).andExpect(status().isOk());
        mvc.perform(head("/api/resources")).andExpect(status().isOk());
        mvc.perform(options("/api/resources")).andExpect(status().isOk());
        verify(resources, never()).create(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/users/me", "/api/reservations/me", "/api/slots/1/waitlist"})
    void existingProtectedPathsStillRejectAnonymousRequests(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/users/me", "/api/reservations/me", "/api/slots/1/waitlist"})
    void verifiedIdentityStillReachesProtectedHandlers(String path) throws Exception {
        mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.generateToken(7L)))
                .andExpect(status().isOk()).andExpect(content().string("7"));
    }

    private static ComputeResource resource() {
        return new ComputeResource(1L, "test-resource", "GPU", "AVAILABLE",
                LocalDateTime.of(2099, 1, 1, 0, 0));
    }
}
