package com.smartmeal.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartmeal.domain.entity.UserAllergy;
import org.apache.ibatis.annotations.Mapper;

/** t_user_allergy 用户过敏原 */
@Mapper
public interface UserAllergyMapper extends BaseMapper<UserAllergy> {
}
