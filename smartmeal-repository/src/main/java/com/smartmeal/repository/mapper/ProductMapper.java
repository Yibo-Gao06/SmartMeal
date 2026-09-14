package com.smartmeal.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartmeal.domain.entity.Product;
import org.apache.ibatis.annotations.Mapper;

/** t_product 商品 SPU */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {
}
