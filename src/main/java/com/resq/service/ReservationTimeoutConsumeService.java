package com.resq.service;

import com.resq.mapper.MqConsumeRecordMapper;
import com.resq.mapper.ReservationMapper;
import com.resq.mq.ReservationTimeoutEvent;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class ReservationTimeoutConsumeService {

    public enum ConsumeResult {
        EXPIRED,
        NO_TRANSITION,
        NOT_YET_DUE
    }

    private final MqConsumeRecordMapper consumeRecordMapper;
    private final ReservationMapper reservationMapper;
    private final ReservationService reservationService;

    public ReservationTimeoutConsumeService(
            MqConsumeRecordMapper consumeRecordMapper,
            ReservationMapper reservationMapper,
            ReservationService reservationService) {

        this.consumeRecordMapper = consumeRecordMapper;
        this.reservationMapper = reservationMapper;
        this.reservationService = reservationService;
    }

    @Transactional
    public ConsumeResult consume(ReservationTimeoutEvent event) {

        if (event == null
                || event.reservationId() == null
                || event.reservationId() <= 0
                || !"RESERVATION_EXPIRE".equals(event.eventType())
                || !Objects.equals(
                        event.eventId(),
                        "reservation-expire-" + event.reservationId())) {

            throw new IllegalArgumentException("非法预约超时事件");
        }

        // 过早到达：不记录为已完成事件
        if (reservationMapper.countPendingNotDue(
                event.reservationId()) > 0) {

            return ConsumeResult.NOT_YET_DUE;
        }

        // 相同 eventId 只能成功插入一次
        consumeRecordMapper.insert(event.eventId());

        // 调用现有的业务事务方法
        boolean expired = reservationService
                .expireIfPendingAndDue(event.reservationId());

        return expired
                ? ConsumeResult.EXPIRED
                : ConsumeResult.NO_TRANSITION;
    }
}