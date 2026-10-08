package com.resq.controller;

import com.resq.domain.ComputeResource;
import com.resq.domain.ResourceSlot;
import com.resq.exception.GlobalExceptionHandler;
import com.resq.mapper.ResourceMapper;
import com.resq.mapper.ResourceSlotMapper;
import com.resq.service.ResourceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ResourceSlotsTest {

    private ResourceMapper resourceMapper;
    private ResourceSlotMapper slotMapper;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        resourceMapper = mock(ResourceMapper.class);
        slotMapper = mock(ResourceSlotMapper.class);
        ResourceService service = new ResourceService(resourceMapper, slotMapper);
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = standaloneSetup(new ResourceController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void existingResourceReturnsSlotsAsJson() throws Exception {
        when(resourceMapper.findById(1L)).thenReturn(new ComputeResource());
        when(slotMapper.findByResourceId(1L)).thenReturn(List.of(new ResourceSlot(
                10L, 1L, LocalDateTime.of(2026, 10, 6, 9, 0),
                LocalDateTime.of(2026, 10, 6, 10, 0), "AVAILABLE")));

        mvc.perform(get("/api/resources/1/slots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(10))
                .andExpect(jsonPath("$[0].resourceId").value(1))
                .andExpect(jsonPath("$[0].slotStart").value("2026-10-06T09:00:00"))
                .andExpect(jsonPath("$[0].slotEnd").value("2026-10-06T10:00:00"))
                .andExpect(jsonPath("$[0].status").value("AVAILABLE"));
        var order = inOrder(resourceMapper, slotMapper);
        order.verify(resourceMapper).findById(1L);
        order.verify(slotMapper).findByResourceId(1L);
    }

    @Test
    void existingResourceWithoutSlotsReturnsEmptyList() throws Exception {
        when(resourceMapper.findById(2L)).thenReturn(new ComputeResource());
        when(slotMapper.findByResourceId(2L)).thenReturn(List.of());

        mvc.perform(get("/api/resources/2/slots"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void missingResourceUsesExisting404WithoutQueryingSlots() throws Exception {
        mvc.perform(get("/api/resources/999/slots"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Resource not found: 999"));
        verifyNoInteractions(slotMapper);
    }
}
