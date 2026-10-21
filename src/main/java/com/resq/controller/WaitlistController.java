package com.resq.controller;

import com.resq.service.WaitlistService;
import com.resq.vo.WaitlistVO;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/slots")
public class WaitlistController {

    private final WaitlistService waitlistService;

    public WaitlistController(WaitlistService waitlistService) {
        this.waitlistService = waitlistService;
    }

    // 用户加入指定时隙的候补队列
    @PostMapping("/{slotId}/waitlist")
    public WaitlistVO join(
            @PathVariable Long slotId,
            @RequestAttribute("currentUserId") Long userId) {

        return waitlistService.join(slotId, userId);
    }

    // 查询当前用户的候补排名
    @GetMapping("/{slotId}/waitlist")
    public WaitlistVO get(
            @PathVariable Long slotId,
            @RequestAttribute("currentUserId") Long userId) {

        return waitlistService.get(slotId, userId);
    }
}