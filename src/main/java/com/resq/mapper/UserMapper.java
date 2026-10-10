
package com.resq.mapper;

import com.resq.domain.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {

    // 根据用户名查找用户
    User findByUsername(
            @Param("username") String username
    );
    User findById(@Param("id") Long id);
    // 插入用户，成功后回填自增 ID
    int insert(User user);
}
