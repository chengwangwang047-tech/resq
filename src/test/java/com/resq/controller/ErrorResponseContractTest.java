package com.resq.controller;

import com.resq.domain.ComputeResource;
import com.resq.dto.ResourceCreateDTO;
import com.resq.exception.GlobalExceptionHandler;
import com.resq.exception.ReservationConflictException;
import com.resq.service.ReservationLockService;
import com.resq.service.ReservationService;
import com.resq.service.ResourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ErrorResponseContractTest {
    private MockMvc mvc;
    private ReservationService reservations;
    private ReservationLockService locks;
    private ResourceService resources;

    @BeforeEach void setUp() {
        reservations = mock(ReservationService.class);
        locks = mock(ReservationLockService.class);
        resources = mock(ResourceService.class);
        when(resources.create(any(ResourceCreateDTO.class)))
                .thenReturn(new ComputeResource(1L, "test", "GPU", "AVAILABLE", null));
        mvc = MockMvcBuilders.standaloneSetup(new ReservationController(reservations, locks),
                        new ResourceController(resources))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void expectedCancellationConflictReturns409() throws Exception {
        when(reservations.cancel(7L, 1L)).thenThrow(new ReservationConflictException("预约状态不可取消"));
        mvc.perform(delete("/api/reservations/1").requestAttr("currentUserId", 7L))
                .andExpect(status().isConflict()).andExpect(jsonPath("success").value(false))
                .andExpect(jsonPath("message").value("预约状态不可取消"));
    }

    @Test void internalReleaseFailureRemains500WithoutInternalDetails() throws Exception {
        when(reservations.cancel(7L, 1L)).thenThrow(new IllegalStateException("internal_database_details"));
        mvc.perform(delete("/api/reservations/1").requestAttr("currentUserId", 7L))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("success").value(false))
                .andExpect(jsonPath("message").value("服务器内部错误，请稍后重试"))
                .andExpect(content().string(not(containsString("internal_database_details"))));
    }

    @Test void frameworkBadRequestsKeep400AndErrorEnvelope() throws Exception {
        mvc.perform(post("/api/reservations").requestAttr("currentUserId", 7L)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"slotId\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("success").value(false));
        mvc.perform(get("/api/reservations/not-a-number").requestAttr("currentUserId", 7L))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("success").value(false));
        mvc.perform(post("/api/resources").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("success").value(false));
        verify(resources, never()).create(any());
        verifyNoInteractions(locks, reservations);
    }

    @Test void framework415IsNotConvertedTo500() throws Exception {
        mvc.perform(post("/api/resources").contentType(MediaType.TEXT_PLAIN).content("invalid"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("success").value(false));
        mvc.perform(put("/api/resources").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("success").value(false))
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(header().string("Allow", containsString("POST")));
    }

    @Test void explicitResponseStatusRetainsStatusAndReason() throws Exception {
        when(locks.create(eq(7L), any())).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求格式不合法"));
        mvc.perform(post("/api/reservations").requestAttr("currentUserId", 7L)
                        .header("X-Request-Id", "contract-test").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slotId\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("message").value("请求格式不合法"));
    }

    @Test void oversizedNameOrTypeIsRejectedBeforeDatabaseService() throws Exception {
        mvc.perform(post("/api/resources").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "n".repeat(101) + "\",\"type\":\"GPU\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("success").value(false));
        mvc.perform(post("/api/resources").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"valid\",\"type\":\"" + "t".repeat(21) + "\"}"))
                .andExpect(status().isBadRequest());
        verify(resources, never()).create(any());
    }

    @Test void maximumValidLengthsRemainAccepted() throws Exception {
        mvc.perform(post("/api/resources").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "n".repeat(100) + "\",\"type\":\"" + "t".repeat(20) + "\"}"))
                .andExpect(status().isCreated());
        verify(resources).create(any());
    }
}
