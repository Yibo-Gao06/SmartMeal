package com.smartmeal.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartmeal.domain.entity.User;
import org.apache.ibatis.annotations.Mapper;

/** t_user 用户 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
