package com.resq.service;

import com.resq.domain.Reservation;
import com.resq.cache.ResourceSlotCacheInvalidator;
import com.resq.dto.ReservationCreateDTO;
import com.resq.exception.ReservationConflictException;
import com.resq.mapper.ReservationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;


class ReservationServiceTest {

    private ReservationMapper mapper;
    private ReservationService service;
    private ResourceSlotCacheInvalidator invalidator;
    private org.springframework.context.ApplicationEventPublisher publisher;

    @BeforeEach
    void setUp() {
        mapper = mock(ReservationMapper.class);
        invalidator = mock(ResourceSlotCacheInvalidator.class);
        publisher = mock(org.springframework.context.ApplicationEventPublisher.class);
        service = new ReservationService(mapper, invalidator, publisher);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "confirmTimeoutSeconds", 600L);
    }

    @Test
    void findByUserIdDelegatesUsingTheProvidedServerUserId() {
        Reservation reservation = new Reservation();
        reservation.setUserId(7L);
        var expected = List.of(reservation);
        when(mapper.findByUserId(7L)).thenReturn(expected);
        assertEquals(expected, service.findByUserId(7L));
        verify(mapper).findByUserId(7L);
    }

    @Test
    void userWithoutReservationsGetsEmptyList() {
        when(mapper.findByUserId(7L)).thenReturn(List.of());
        assertTrue(service.findByUserId(7L).isEmpty());
    }

    @Test
    void databaseFailureIsNotConvertedToEmptyList() {
        RuntimeException failure = new RuntimeException("database unavailable");
        when(mapper.findByUserId(7L)).thenThrow(failure);
        assertSame(failure, assertThrows(RuntimeException.class,
                () -> service.findByUserId(7L)));
    }

   
@Test
void findByIdChecksOwnershipAndMissingReservation() {
    Reservation reservation = new Reservation();
    reservation.setId(100L);
    reservation.setUserId(7L);

    when(mapper.findById(100L)).thenReturn(reservation);

    // 本人可以查询
    assertSame(reservation, service.findById(7L, 100L));

    // 其他用户不能查询
    assertThrows(SecurityException.class,
            () -> service.findById(8L, 100L));

    // 预约不存在
    assertThrows(NoSuchElementException.class,
            () -> service.findById(7L, 999L));
}

@Test
void creatingReservationRejectsBusySlotWithoutInsert() {
    ReservationCreateDTO dto =
            new ReservationCreateDTO(10L, "request-001");

    // 模拟时隙已被占用
    when(mapper.reserveSlotIfAvailable(10L)).thenReturn(0);

    // 创建预约应该失败
    assertThrows(ReservationConflictException.class,
            () -> service.create(7L, dto));

    // 抢占失败后不能执行 INSERT
    verify(mapper, never()).insert(any());
    verifyNoInteractions(invalidator, publisher);
}

@Test
void cancellingChecksOwnershipStatusAndAffectedRows() {
    Reservation reservation = new Reservation();
    reservation.setId(100L);
    reservation.setUserId(7L);
    reservation.setSlotId(10L);
    reservation.setStatus("PENDING");

    when(mapper.findById(100L)).thenReturn(reservation);

    when(mapper.updateStatusIfCurrent(
            100L, 7L, "PENDING", "CANCELLED"))
            .thenReturn(1);

    when(mapper.releaseReservedSlot(10L)).thenReturn(1);

    // 第一次取消成功
    Reservation result = service.cancel(7L, 100L);

    assertEquals("CANCELLED", result.getStatus());
    verify(mapper).releaseReservedSlot(10L);
    verify(invalidator).evictAfterCommit(10L);
    reset(invalidator);

    // 模拟数据库中预约已经取消
    reset(mapper);
    when(mapper.findById(100L)).thenReturn(reservation);
    when(mapper.updateStatusIfCurrent(
            100L, 7L, "PENDING", "CANCELLED"))
            .thenReturn(0);

    // 重复取消应该失败
    assertThrows(ReservationConflictException.class,
            () -> service.cancel(7L, 100L));

    // 重复取消绝对不能再次释放时隙
    verify(mapper, never()).releaseReservedSlot(anyLong());
    verifyNoInteractions(invalidator);

    // 其他用户也不能取消
    reset(mapper);
    when(mapper.findById(100L)).thenReturn(reservation);

    assertThrows(SecurityException.class,
            () -> service.cancel(8L, 100L));

    verify(mapper, never()).releaseReservedSlot(anyLong());
    verifyNoInteractions(invalidator);
}


    @Test
    void successfulCreationSchedulesSlotCacheEviction() {
        var dto = new ReservationCreateDTO(10L, "D15-create");
        when(mapper.reserveSlotIfAvailable(10L)).thenReturn(1);
        when(mapper.insert(any())).thenAnswer(invocation -> {
            Reservation row = invocation.getArgument(0);
            row.setId(100L);
            return 1;
        });
        Reservation created = service.create(7L, dto);
        assertEquals(100L, created.getId());
        assertEquals(created.getCreatedAt().plusSeconds(600), created.getExpireAt());
        var event = org.mockito.ArgumentCaptor.forClass(com.resq.event.ReservationCreatedEvent.class);
        verify(publisher).publishEvent(event.capture());
        assertEquals(created.getId(), event.getValue().reservationId());
        assertEquals(created.getSlotId(), event.getValue().slotId());
        assertEquals(created.getUserId(), event.getValue().userId());
        assertEquals(created.getRequestId(), event.getValue().requestId());
        assertEquals("PENDING", event.getValue().status());
        var order = inOrder(mapper, invalidator, publisher);
        order.verify(mapper).reserveSlotIfAvailable(10L);
        order.verify(mapper).insert(any());
        order.verify(invalidator).evictAfterCommit(10L);
        order.verify(publisher).publishEvent(event.getValue());
    }

    @Test
    void insertFailureDoesNotScheduleEviction() {
        when(mapper.reserveSlotIfAvailable(10L)).thenReturn(1);
        when(mapper.insert(any())).thenReturn(0);
        assertThrows(IllegalStateException.class,
                () -> service.create(7L, new ReservationCreateDTO(10L, "D15-failed")));
        verifyNoInteractions(invalidator, publisher);
    }

    @Test
    void releaseFailureDoesNotScheduleEviction() {
        var reservation = new Reservation();
        reservation.setId(100L);
        reservation.setUserId(7L);
        reservation.setSlotId(10L);
        when(mapper.findById(100L)).thenReturn(reservation);
        when(mapper.updateStatusIfCurrent(100L, 7L, "PENDING", "CANCELLED")).thenReturn(1);
        when(mapper.releaseReservedSlot(10L)).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.cancel(7L, 100L));
        verifyNoInteractions(invalidator);
    }

    // 真正的事务回滚由 Day17LockTransactionTest 和 ReservationCancellationHttpTest 验证；
    // 这里的 Mockito 测试只验证服务调用与异常分支。
}
