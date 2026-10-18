package com.resq.service;

import com.resq.domain.Reservation;
import com.resq.cache.ResourceSlotCacheInvalidator;
import com.resq.dto.ReservationCreateDTO;
import com.resq.mapper.ReservationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.resq.exception.ReservationConflictException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import com.resq.event.ReservationCreatedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import java.time.temporal.ChronoUnit;

@Service
public class ReservationService {

    private final ReservationMapper reservationMapper;

    private final ResourceSlotCacheInvalidator slotCacheInvalidator;
    private final ApplicationEventPublisher eventPublisher;
    @Value("${resq.reservation.confirm-timeout-seconds:600}")
    private long confirmTimeoutSeconds;

    public ReservationService(
            ReservationMapper reservationMapper,
            ResourceSlotCacheInvalidator slotCacheInvalidator,
            ApplicationEventPublisher eventPublisher) {

        this.reservationMapper = reservationMapper;
        this.slotCacheInvalidator = slotCacheInvalidator;
        this.eventPublisher = eventPublisher;
    }

    /** authenticatedUserId 由受信任的服务端身份上下文提供，不能来自客户端自报。 */
    public Reservation findById(Long authenticatedUserId, Long id) {

        // 1. 参数校验
        if (authenticatedUserId == null || authenticatedUserId <= 0) {
            throw new IllegalArgumentException("用户 ID 不合法");
        }

        if (id == null || id <= 0) {
            throw new IllegalArgumentException("预约 ID 不合法");
        }

        // 2. 查询数据库
        Reservation result = reservationMapper.findById(id);

        // 3. 检查预约是否存在
        if (result == null) {
            throw new NoSuchElementException("预约不存在，id = " + id);
        }

        // 4. 校验预约归属
        if (!authenticatedUserId.equals(result.getUserId())) {
            throw new SecurityException("无权访问其他用户的预约");
        }

        // 5. 返回结果
        return result;
    }

    /** Day13 仅底层查询；Day14 才由 JWT 身份调用，当前没有 /me 接口。 */
    public List<Reservation> findByUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException();
        }
        return reservationMapper.findByUserId(userId);
    }

    /**
     * Day18：查询历史预约并判断是否为合法重试。
     *
     * 返回 null：该 requestId 尚无历史预约。
     * 返回 Reservation：同一次业务操作已经成功。
     * 抛出冲突异常：requestId 被用于其他业务操作。
     */
    public Reservation findExistingForRetry(
            Long authenticatedUserId,
            ReservationCreateDTO dto) {

        Reservation existing = reservationMapper.findByRequestId(dto.getRequestId());

        if (existing == null) {
            return null;
        }

        if (!authenticatedUserId.equals(existing.getUserId())
                || !dto.getSlotId().equals(existing.getSlotId())) {

            throw new ReservationConflictException(
                    "requestId 已用于其他预约操作");
        }

        return existing;
    }

    @Transactional
    public Reservation create(
            Long authenticatedUserId,
            ReservationCreateDTO dto) {

        // 1. 校验用户 ID
        if (authenticatedUserId == null || authenticatedUserId <= 0) {
            throw new IllegalArgumentException("用户 ID 不合法");
        }

        // 2. 校验时隙 ID
        if (dto == null || dto.getSlotId() == null || dto.getSlotId() <= 0) {
            throw new IllegalArgumentException("时隙 ID 不合法");
        }

        // 3. 校验 requestId
        String requestId = dto.getRequestId();

        if (requestId == null || requestId.isBlank()
                || requestId.length() > 100) {
            throw new IllegalArgumentException("requestId 不合法");
        }

        // ===== Day18 新增：幂等检查 =====

        // 4. 根据 requestId 查询历史预约
        Reservation existing = findExistingForRetry(authenticatedUserId, dto);

        if (existing != null) {
            return existing;
        }

        // ===== 以下保留原来的创建流程 =====

        // 7. 原子抢占时隙
        int rows = reservationMapper
                .reserveSlotIfAvailable(dto.getSlotId());

        if (rows != 1) {
            throw new ReservationConflictException(
                    "时隙不可用，预约失败");
        }

        // 8. 创建预约对象

        // 统一使用同一个时间基准
        LocalDateTime now = LocalDateTime.now()
                .truncatedTo(ChronoUnit.SECONDS);

        // 计算确认截止时间
        LocalDateTime expireAt = now.plusSeconds(confirmTimeoutSeconds);

        // 创建预约对象
        Reservation reservation = new Reservation(
                null,
                authenticatedUserId,
                dto.getSlotId(),
                "PENDING",
                requestId,
                now);

        // 设置确认截止时间
        reservation.setExpireAt(expireAt);

        // 9. 插入数据库
        int inserted = reservationMapper.insert(reservation);

        if (inserted != 1 || reservation.getId() == null) {
            throw new IllegalStateException("预约记录创建失败");
        }

        // 10. 事务提交后失效时隙缓存
        // 10. 事务提交后失效时隙缓存
        slotCacheInvalidator.evictAfterCommit(dto.getSlotId());

        // 11. 发布预约创建事件
        eventPublisher.publishEvent(
                new ReservationCreatedEvent(
                        reservation.getId(),
                        reservation.getUserId(),
                        reservation.getSlotId(),
                        reservation.getRequestId(),
                        reservation.getStatus()));

        return reservation;
    }

    @Transactional
    public Reservation cancel(Long authenticatedUserId, Long id) {
        // TODO(D13-TX): 自己添加 @Transactional；取消预约与释放时隙原子完成。

        // TODO(D13-SVC-4): 查预约、处理不存在、校验归属与可取消状态。
        // 1. 参数校验
        if (authenticatedUserId == null || authenticatedUserId <= 0) {
            throw new IllegalArgumentException("用户 ID 不合法");
        }

        if (id == null || id <= 0) {
            throw new IllegalArgumentException("预约 ID 不合法");
        }

        // 2. 查询数据库
        Reservation result = reservationMapper.findById(id);

        // 3. 检查预约是否存在
        if (result == null) {
            throw new NoSuchElementException("预约不存在，id = " + id);
        }

        // 4. 校验预约归属
        if (!authenticatedUserId.equals(result.getUserId())) {
            throw new SecurityException("无权访问其他用户的预约");
        }
        // 1. 先成功取消预约
        int updated = reservationMapper.updateStatusIfCurrent(
                id, authenticatedUserId, "PENDING", "CANCELLED");

        if (updated != 1) {
            throw new IllegalStateException("取消预约失败");
        }

        // 2. 再释放对应的时隙
        int released = reservationMapper.releaseReservedSlot(
                result.getSlotId());

        if (released != 1) {
            throw new IllegalStateException("释放时隙失败");
        }
        slotCacheInvalidator.evictAfterCommit(result.getSlotId());
        result.setStatus("CANCELLED");
        return result;
    }

    /**
     * Day20：确认预约。
     *
     * 只有预约所属用户才允许操作。
     * 只有未超过截止时间的 PENDING 预约可以确认。
     */
    @Transactional
    public Reservation confirm(
            Long authenticatedUserId,
            Long reservationId) {

        // 1. 查询预约并校验用户归属
        Reservation existing = findById(
                authenticatedUserId,
                reservationId);

        // 2. 已经确认的预约，直接返回
        if ("CONFIRMED".equals(existing.getStatus())) {
            return existing;
        }

        // 3. MySQL 条件更新，尝试确认预约
        int updated = reservationMapper
                .confirmIfPendingAndNotExpired(
                        reservationId,
                        authenticatedUserId);

        // 4. 更新失败，重新检查状态
        if (updated != 1) {

            Reservation latest = findById(
                    authenticatedUserId,
                    reservationId);

            // 可能是另一个并发确认请求已经成功
            if ("CONFIRMED".equals(latest.getStatus())) {
                return latest;
            }

            throw new ReservationConflictException(
                    "预约已取消、已过期或超过确认期限，无法确认");
        }

        // 5. 返回更新后的预约
        return findById(authenticatedUserId, reservationId);
    }

    /**
     * Day20：系统自动过期预约。
     *
     * @return true 本次成功过期并释放时隙
     *         false 本次没有发生过期状态转换
     */
    @Transactional
    public boolean expireIfPendingAndDue(Long reservationId) {

        // 1. 校验预约 ID
        if (reservationId == null || reservationId <= 0) {
            throw new IllegalArgumentException("预约 ID 不合法");
        }

        // 2. 查找预约，获取它占用的时隙
        Reservation reservation = reservationMapper.findById(reservationId);

        if (reservation == null) {
            return false;
        }

        // 3. 只有到期且仍为 PENDING，才转换为 EXPIRED
        int updated = reservationMapper
                .expireIfPendingAndDue(reservationId);

        // 4. 未发生状态转换，不能释放时隙
        if (updated == 0) {
            return false;
        }

        // 5. 释放这条预约对应的时隙
        int released = reservationMapper
                .releaseReservedSlot(reservation.getSlotId());

        if (released != 1) {
            throw new IllegalStateException(
                    "预约过期后释放时隙失败");
        }

        // 6. 事务提交后失效相关时隙缓存
        slotCacheInvalidator.evictAfterCommit(
                reservation.getSlotId());

        return true;
    }
}
