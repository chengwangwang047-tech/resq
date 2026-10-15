package com.resq.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.resq.domain.ResourceSlot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResourceSlotCacheTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 6);
    private static final String KEY = "resource:1:slots:2026-10-06";
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private ResourceSlotCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        cache = new ResourceSlotCache(redis, mapper);
    }

    @Test
    void resourceSlotListRoundTripsLocalDateTimeWithFiveMinuteBaseAndJitterTtl() {
        var slot = new ResourceSlot(10L, 1L, DATE.atTime(9, 0, 1),
                DATE.atTime(10, 0), "AVAILABLE");
        cache.put(1L, DATE, List.of(slot));
        var json = ArgumentCaptor.forClass(String.class);
        var ttl = ArgumentCaptor.forClass(Duration.class);
        verify(values).set(eq(KEY), json.capture(), ttl.capture());
        assertTrue(ttl.getValue().compareTo(Duration.ofSeconds(300)) >= 0);
        assertTrue(ttl.getValue().compareTo(Duration.ofSeconds(360)) <= 0);
        assertTrue(json.getValue().contains("2026-10-06T09:00:01"));
        when(values.get(KEY)).thenReturn(json.getValue());
        var result = cache.get(1L, DATE);
        assertNotNull(result);
        assertInstanceOf(ResourceSlot.class, result.get(0));
        assertEquals(slot.getId(), result.get(0).getId());
        assertEquals(slot.getResourceId(), result.get(0).getResourceId());
        assertEquals(slot.getSlotStart(), result.get(0).getSlotStart());
        assertEquals(slot.getSlotEnd(), result.get(0).getSlotEnd());
        assertEquals(slot.getStatus(), result.get(0).getStatus());
    }

    @Test
    void emptyListIsStoredAndDistinctFromMiss() {
        assertNull(cache.get(1L, DATE));
        cache.put(1L, DATE, List.of());
        var ttl = ArgumentCaptor.forClass(Duration.class);
        verify(values).set(eq(KEY), eq("[]"), ttl.capture());
        assertTrue(ttl.getValue().compareTo(Duration.ofSeconds(300)) >= 0);
        assertTrue(ttl.getValue().compareTo(Duration.ofSeconds(360)) <= 0);
        when(values.get(KEY)).thenReturn("[]");
        assertNotNull(cache.get(1L, DATE));
        assertTrue(cache.get(1L, DATE).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{broken", "null", "[null]", "{}"})
    void invalidJsonIsTreatedAsMiss(String json) {
        when(values.get(KEY)).thenReturn(json);
        assertNull(cache.get(1L, DATE));
    }

    @Test
    void redisFailureAllowsReadFallbackAndDoesNotFailWriteOrEviction() {
        when(values.get(KEY)).thenThrow(new DataAccessResourceFailureException("redis unavailable"));
        assertNull(cache.get(1L, DATE));
        doThrow(new DataAccessResourceFailureException("redis unavailable"))
                .when(values).set(eq(KEY), anyString(), any(Duration.class));
        assertDoesNotThrow(() -> cache.put(1L, DATE, List.of()));
        when(redis.delete(KEY)).thenThrow(new DataAccessResourceFailureException("redis unavailable"));
        assertDoesNotThrow(() -> cache.evict(1L, DATE));
    }

    @Test
    void serializationFailureDoesNotWriteRedisOrFailRequest() throws Exception {
        var mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("json error") {});
        var failingCache = new ResourceSlotCache(redis, mapper);
        assertDoesNotThrow(() -> failingCache.put(1L, DATE, List.of()));
        verifyNoInteractions(values);
    }

    @Test
    void evictionOnlyDeletesExactResourceAndDateKey() {
        cache.evict(1L, DATE);
        verify(redis).delete(KEY);
        verifyNoMoreInteractions(redis);
    }
}
