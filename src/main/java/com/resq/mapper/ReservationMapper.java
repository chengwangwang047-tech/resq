package com.resq.mapper;

import com.resq.domain.Reservation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDateTime;
import java.util.List;

/** 正式预约数据契约；XML 未填时调用会抛 BindingException，不代表持久化已完成。 */
@Mapper
public interface ReservationMapper {

        /** TODO(D13-RES-1): 按 id 查询；不存在返回 null。 */
        Reservation findById(@Param("id") Long id);

        /** TODO(D13-RES-2): 仅底层按 userId 查询，空结果为 []；不作为身份认证。 */
        List<Reservation> findByUserId(@Param("userId") Long userId);

        /** TODO(D13-RES-3): 插入并回填 id；返回影响行数。 */
        int insert(Reservation reservation);

        /** TODO(D13-RES-4): 条件转换状态；影响行数为 0 时由 Service 判定失败。 */
        int updateStatusIfCurrent(@Param("id") Long id,
                        @Param("userId") Long userId,
                        @Param("expectedStatus") String expectedStatus,
                        @Param("newStatus") String newStatus);

        /** TODO(D13-CONFLICT): 原子抢占 AVAILABLE 时隙，返回影响行数；沿用 RESERVED 状态。 */
        int reserveSlotIfAvailable(@Param("slotId") Long slotId);

        /** TODO(D13-CANCEL): 仅在本次取消成功且归属已校验后，在同一事务里释放 RESERVED 时隙。 */
        int releaseReservedSlot(@Param("slotId") Long slotId);

        /**
         * 只有预约到期且仍为 PENDING，才转换为 EXPIRED。
         *
         * @return 更新成功的行数，通常为 0 或 1
         */
        int expireIfPendingAndDue(@Param("id") Long id);

        Reservation findByRequestId(
                        @Param("requestId") String requestId);

        /**
         * Day20：用户确认预约。
         * 只有本人、PENDING、且未超过确认期限，才允许确认。
         */
        int confirmIfPendingAndNotExpired(
                        @Param("id") Long id,
                        @Param("userId") Long userId);

        /**
         * Day20：查询已经到期但仍为 PENDING 的预约 ID。
         * 每次最多查询 limit 条。
         */
        List<Long> findDuePendingReservationIds(
                        @Param("limit") int limit);

        @Select("""
                        SELECT COUNT(*)
                        FROM reservation
                        WHERE id = #{id}
                          AND status = 'PENDING'
                          AND expire_at > NOW(3)
                        """)
        int countPendingNotDue(@Param("id") Long id);
        // Day23：预约历史游标分页
List<Reservation> findPageByUserId(
        @Param("userId") Long userId,
        @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
        @Param("cursorId") Long cursorId,
        @Param("limit") int limit);
}
