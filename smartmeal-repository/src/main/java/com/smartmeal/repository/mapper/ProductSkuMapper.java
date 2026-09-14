package com.smartmeal.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartmeal.domain.entity.ProductSku;
import org.apache.ibatis.annotations.Mapper;

/** t_product_sku 商品 SKU */
@Mapper
public interface ProductSkuMapper extends BaseMapper<ProductSku> {
}
