package com.resq.service;

import com.resq.domain.User;
import com.resq.mapper.UserMapper;
import com.resq.vo.UserProfileVO;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserService {

    private final UserMapper userMapper;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public UserProfileVO getProfile(Long userId) {

        // 1. 根据当前登录用户 ID 查询数据库
        User user = userMapper.findById(userId);

        // 2. 用户不存在
        if (user == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "用户不存在");
        }

        // 3. 转换成对外返回的 VO
        return new UserProfileVO(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getCreatedAt()
        );
    }
}
