package com.resq.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.resq.cache.ResourceCache;
import com.resq.cache.ResourceSlotCache;
import com.resq.domain.ComputeResource;
import com.resq.domain.ResourceSlot;
import com.resq.exception.GlobalExceptionHandler;
import com.resq.mapper.ResourceMapper;
import com.resq.mapper.ResourceSlotMapper;
import com.resq.service.ResourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ResourceSlotsTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 6);
    private ResourceMapper resourceMapper;
    private ResourceSlotMapper slotMapper;
    private ResourceCache resourceCache;
    private ResourceSlotCache slotCache;
    private ResourceService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        resourceMapper = mock(ResourceMapper.class);
        slotMapper = mock(ResourceSlotMapper.class);
        resourceCache = mock(ResourceCache.class);
        slotCache = mock(ResourceSlotCache.class);
        when(resourceCache.get(anyLong())).thenReturn(
                new ResourceCache.CacheResult(ResourceCache.CacheStatus.MISS, null));
        when(slotCache.get(anyLong(), any(LocalDate.class))).thenReturn(null);
        service = new ResourceService(resourceMapper, slotMapper, resourceCache, slotCache);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = standaloneSetup(new ResourceController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    private List<ResourceSlot> slots() {
        return List.of(new ResourceSlot(10L, 1L, DATE.atTime(9, 0),
                DATE.atTime(10, 0), "AVAILABLE"));
    }

    @Test
    void existingResourceReturnsAllSlotsWhenDateIsOmitted() throws Exception {
        when(resourceMapper.findById(1L)).thenReturn(new ComputeResource());
        when(slotMapper.findByResourceId(1L)).thenReturn(slots());
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
        verifyNoInteractions(slotCache);
    }

    @Test
    void existingResourceWithoutSlotsReturnsEmptyListWithoutDate() throws Exception {
        when(resourceMapper.findById(2L)).thenReturn(new ComputeResource());
        when(slotMapper.findByResourceId(2L)).thenReturn(List.of());
        mvc.perform(get("/api/resources/2/slots"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void datedMissQueriesAndPopulatesCacheThenHitAvoidsSlotQuery() throws Exception {
        var expected = slots();
        when(resourceMapper.findById(1L)).thenReturn(new ComputeResource());
        when(slotCache.get(1L, DATE)).thenReturn(null, expected);
        when(slotMapper.findByResourceIdAndDate(1L, DATE)).thenReturn(expected);
        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/api/resources/1/slots").param("date", DATE.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].slotStart").value("2026-10-06T09:00:00"));
        }
        verify(slotMapper).findByResourceIdAndDate(1L, DATE);
        verify(slotMapper, never()).findByResourceId(any());
        verify(slotCache).put(1L, DATE, expected);
    }

    @Test
    void emptyListHitDoesNotQueryDatabaseSlots() throws Exception {
        when(resourceMapper.findById(1L)).thenReturn(new ComputeResource());
        when(slotCache.get(1L, DATE)).thenReturn(List.of());
        mvc.perform(get("/api/resources/1/slots").param("date", DATE.toString()))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verifyNoInteractions(slotMapper);
        verify(slotCache, never()).put(any(), any(), any());
    }

    @Test
    void emptyDatabaseResultIsCached() throws Exception {
        when(resourceMapper.findById(1L)).thenReturn(new ComputeResource());
        when(slotMapper.findByResourceIdAndDate(1L, DATE)).thenReturn(List.of());
        mvc.perform(get("/api/resources/1/slots").param("date", DATE.toString()))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(slotCache).put(1L, DATE, List.of());
    }

    @Test
    void missingResourceKeeps404BeforeReadingSlotsOrDateCache() throws Exception {
        for (String uri : List.of("/api/resources/999/slots",
                "/api/resources/999/slots?date=2026-10-06")) {
            mvc.perform(get(uri)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("Resource not found: 999"));
        }
        verifyNoInteractions(slotMapper, slotCache);
    }

    @Test
    void malformedOrImpossibleDateReturns400BeforeDatabaseOrCache() throws Exception {
        for (String date : List.of("bad-date", "2026-02-30", "2026/10/06")) {
            mvc.perform(get("/api/resources/1/slots").param("date", date))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(resourceMapper, resourceCache, slotMapper, slotCache);
    }

    @Test
    void databaseFailureIsNotConvertedToOrCachedAsAnEmptyList() {
        when(resourceMapper.findById(1L)).thenReturn(new ComputeResource());
        when(slotMapper.findByResourceIdAndDate(1L, DATE))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        assertThrows(DataAccessResourceFailureException.class,
                () -> service.findSlotsByResourceId(1L, DATE));
        verify(slotCache, never()).put(any(), any(), any());
    }

    @Test
    void existingResourceDetailCacheRemainsInUse() throws Exception {
        ComputeResource cached = new ComputeResource(
                1L, "existing", "GPU", "AVAILABLE", LocalDateTime.of(2026, 10, 6, 8, 0));
        when(resourceCache.get(1L)).thenReturn(
                new ResourceCache.CacheResult(ResourceCache.CacheStatus.FOUND, cached));
        mvc.perform(get("/api/resources/1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("existing"));
        verifyNoInteractions(resourceMapper, slotMapper, slotCache);
        verify(resourceCache, never()).put(any(), any());
    }

    @Test
    void negativeResourceCacheKeeps404BeforeDatabaseSlotsOrDateCache() throws Exception {
        when(resourceCache.get(999L)).thenReturn(
                new ResourceCache.CacheResult(ResourceCache.CacheStatus.NOT_FOUND, null));
        for (String uri : List.of("/api/resources/999/slots",
                "/api/resources/999/slots?date=2026-10-06")) {
            mvc.perform(get(uri)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("Resource not found: 999"));
        }
        verifyNoInteractions(resourceMapper, slotMapper, slotCache);
        verify(resourceCache, never()).putNotFound(any());
    }
}
