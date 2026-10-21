package com.resq.vo;

public record WaitlistVO(
        Long slotId,
        boolean joined,
        Long position,
        long total
) {
}