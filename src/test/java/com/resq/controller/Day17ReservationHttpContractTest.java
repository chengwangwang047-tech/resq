package com.resq.controller;

import com.resq.domain.Reservation;
import com.resq.dto.ReservationCreateDTO;
import com.resq.exception.GlobalExceptionHandler;
import com.resq.exception.ReservationBusyException;
import com.resq.exception.ReservationConflictException;
import com.resq.service.ReservationLockService;
import com.resq.service.ReservationService;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.client.RedisException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.MissingRequestHeaderException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Isolated MVC contract checks for the actual controller and exception advice.
 * The authenticated request attribute is supplied directly: this class does not
 * exercise JWT authentication, a servlet-container error dispatch, or live HTTP.
 * Dependencies are mocked, so these tests open neither MySQL nor Redis sockets.
 */
class Day17ReservationHttpContractTest {

    private static final Long USER_ID = 900_017L;
    private static final Long SLOT_ID = 900_018L;
    private static final String REQUEST_ID = "day17-http-contract";
    private static final String VALID_BODY =
            "{\"slotId\":900018,\"requestId\":\"day17-http-contract\"}";

    private ReservationService reservationService;
    private ReservationLockService reservationLockService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reservationService = mock(ReservationService.class);
        reservationLockService = mock(ReservationLockService.class);
        ReservationController controller =
                new ReservationController(reservationService, reservationLockService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void validCreateReturns201AndDelegatesToLockWrapper() throws Exception {
        when(reservationLockService.create(eq(USER_ID), any(ReservationCreateDTO.class)))
                .thenReturn(new Reservation());

        mvc.perform(post("/api/reservations")
                        .requestAttr("currentUserId", USER_ID)
                        .header("X-Request-Id", REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated());

        ArgumentCaptor<ReservationCreateDTO> request =
                ArgumentCaptor.forClass(ReservationCreateDTO.class);
        verify(reservationLockService).create(eq(USER_ID), request.capture());
        assertEquals(SLOT_ID, request.getValue().getSlotId());
        assertEquals(REQUEST_ID, request.getValue().getRequestId());
        verifyNoInteractions(reservationService);
    }

    @Test
    void requestHeaderSuppliesRequestIdWhenBodyOmitsIt() throws Exception {
        when(reservationLockService.create(eq(USER_ID), any(ReservationCreateDTO.class)))
                .thenReturn(new Reservation());

        mvc.perform(post("/api/reservations")
                        .requestAttr("currentUserId", USER_ID)
                        .header("X-Request-Id", REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slotId\":900018}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<ReservationCreateDTO> request =
                ArgumentCaptor.forClass(ReservationCreateDTO.class);
        verify(reservationLockService).create(eq(USER_ID), request.capture());
        assertEquals(SLOT_ID, request.getValue().getSlotId());
        assertEquals(REQUEST_ID, request.getValue().getRequestId());
        verifyNoInteractions(reservationService);
    }

    @Test
    void requestHeaderOverridesDifferentRequestIdInBody() throws Exception {
        when(reservationLockService.create(eq(USER_ID), any(ReservationCreateDTO.class)))
                .thenReturn(new Reservation());

        mvc.perform(post("/api/reservations")
                        .requestAttr("currentUserId", USER_ID)
                        .header("X-Request-Id", REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slotId\":900018,\"requestId\":\"ignored-body-request-id\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<ReservationCreateDTO> request =
                ArgumentCaptor.forClass(ReservationCreateDTO.class);
        verify(reservationLockService).create(eq(USER_ID), request.capture());
        assertEquals(SLOT_ID, request.getValue().getSlotId());
        assertEquals(REQUEST_ID, request.getValue().getRequestId());
        verifyNoInteractions(reservationService);
    }

    @Test
    void businessConflictIsMappedTo409() throws Exception {
        when(reservationLockService.create(eq(USER_ID), any(ReservationCreateDTO.class)))
                .thenThrow(new ReservationConflictException("Isolated test slot is unavailable"));

        mvc.perform(post("/api/reservations")
                        .requestAttr("currentUserId", USER_ID)
                        .header("X-Request-Id", REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict());

        verifyNoInteractions(reservationService);
    }

    @Test
    void interruptionBusyExceptionIsMappedTo503() throws Exception {
        ReservationBusyException interruption = new ReservationBusyException(
                "Simulated interrupted lock wait", new InterruptedException("Isolated test"));
        when(reservationLockService.create(eq(USER_ID), any(ReservationCreateDTO.class)))
                .thenThrow(interruption);

        mvc.perform(post("/api/reservations")
                        .requestAttr("currentUserId", USER_ID)
                        .header("X-Request-Id", REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isServiceUnavailable());

        // The real wrapper's interrupt restoration is checked by its unit test.
        verifyNoInteractions(reservationService);
    }

    @Test
    void acquisitionTimeoutBusyExceptionIsMappedTo503() throws Exception {
        ReservationBusyException timeout =
                new ReservationBusyException("预约繁忙，请稍后重试", null);
        when(reservationLockService.create(eq(USER_ID), any(ReservationCreateDTO.class)))
                .thenThrow(timeout);

        mvc.perform(post("/api/reservations")
                        .requestAttr("currentUserId", USER_ID)
                        .header("X-Request-Id", REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isServiceUnavailable());

        // The real lock wrapper separately verifies that failed acquisition
        // throws this exception and never reaches the reservation service.
        verifyNoInteractions(reservationService);
    }

    @Test
    void unexpectedIllegalStateExceptionReturnsSafe500() throws Exception {
        when(reservationLockService.create(eq(USER_ID), any(ReservationCreateDTO.class)))
                .thenThrow(new IllegalStateException("Simulated unexpected state"));
        mvc.perform(post("/api/reservations").requestAttr("currentUserId", USER_ID)
                        .header("X-Request-Id", REQUEST_ID).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("success").value(false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("message").value("服务器内部错误，请稍后重试"));
        verifyNoInteractions(reservationService);
    }

    @Test
    void unexpectedRedisExceptionReturnsSafe500() throws Exception {
        when(reservationLockService.create(eq(USER_ID), any(ReservationCreateDTO.class)))
                .thenThrow(new RedisException("Simulated Redis failure"));
        mvc.perform(post("/api/reservations").requestAttr("currentUserId", USER_ID)
                        .header("X-Request-Id", REQUEST_ID).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("success").value(false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("message").value("服务器内部错误，请稍后重试"));
        verifyNoInteractions(reservationService);
    }

    @Test
    void missingRequestHeaderIsRejectedBeforeCallingServices() throws Exception {
        mvc.perform(post("/api/reservations")
                        .requestAttr("currentUserId", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertInstanceOf(
                        MissingRequestHeaderException.class, result.getResolvedException()));

        verifyNoInteractions(reservationLockService, reservationService);
    }

    @Test
    void missingSlotIdIsRejectedBeforeCallingServices() throws Exception {
        mvc.perform(post("/api/reservations")
                        .requestAttr("currentUserId", USER_ID)
                        .header("X-Request-Id", REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"day17-invalid\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(reservationLockService, reservationService);
    }

    @Test
    void nonpositiveSlotIdIsRejectedBeforeCallingServices() throws Exception {
        mvc.perform(post("/api/reservations")
                        .requestAttr("currentUserId", USER_ID)
                        .header("X-Request-Id", REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slotId\":0,\"requestId\":\"day17-invalid\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(reservationLockService, reservationService);
    }

    @Test
    void blankRequestIdIsRejectedBeforeCallingServices() throws Exception {
        mvc.perform(post("/api/reservations")
                        .requestAttr("currentUserId", USER_ID)
                        .header("X-Request-Id", " ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slotId\":900018,\"requestId\":\" \"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(reservationLockService, reservationService);
    }

    @Test
    void overlongRequestIdIsRejectedBeforeCallingServices() throws Exception {
        String requestId = "x".repeat(101);
        String body = "{\"slotId\":900018,\"requestId\":\"" + requestId + "\"}";
        mvc.perform(post("/api/reservations")
                        .requestAttr("currentUserId", USER_ID)
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(reservationLockService, reservationService);
    }
}
