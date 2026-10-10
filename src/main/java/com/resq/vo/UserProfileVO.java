package com.resq.vo;

import java.time.LocalDateTime;

public record UserProfileVO(
        Long id,
        String username,
        String email,
        LocalDateTime createdAt
) {
}
