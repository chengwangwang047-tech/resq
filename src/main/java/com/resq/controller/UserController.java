package com.resq.controller;

import com.resq.service.UserService;
import com.resq.vo.UserProfileVO;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    // GET /api/users/me
    @GetMapping("/me")
    public UserProfileVO getMyProfile(
            @RequestAttribute("currentUserId") Long userId) {

        return userService.getProfile(userId);
    }
}
