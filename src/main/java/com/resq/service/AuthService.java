
package com.resq.service;

import com.resq.domain.User;
import com.resq.dto.UserRegisterDTO;
import com.resq.mapper.UserMapper;
import com.resq.dto.UserLoginDTO;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

@Service
public class AuthService {

    private final UserMapper userMapper;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public Long register(UserRegisterDTO request) {

        // 1. 检查用户名是否存在
        User existing = userMapper.findByUsername(
                request.username());

        if (existing != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "用户名已存在");
        }

        // 2. 创建用户实体
        User user = new User();

        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setCreatedAt(LocalDateTime.now());

        // 3. 密码安全哈希
        String hash = passwordEncoder.encode(
                request.password());

        user.setPasswordHash(hash);

        // 4. 插入数据库
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "用户名或邮箱已被占用",
                    e);
        }

        // 5. 返回生成的用户 ID
        return user.getId();
    }

    public Long authenticate(UserLoginDTO request) {

        // 1. 根据用户名查找用户
        User user = userMapper.findByUsername(
                request.username());

        // 2. 验证用户是否存在、密码是否正确
        if (user == null ||
                !passwordEncoder.matches(
                        request.password(),
                        user.getPasswordHash())) {

            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "用户名或密码错误");
        }

        // 3. 身份验证成功，返回用户 ID
        return user.getId();
    }

}
