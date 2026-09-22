package com.smartmeal.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartmeal.domain.entity.ProductSku;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** t_product_sku 商品 SKU */
@Mapper
public interface ProductSkuMapper extends BaseMapper<ProductSku> {

    /**
     * 条件扣减库存：仅当库存足够时才扣，返回受影响行数。
     *
     * <p>{@code stock >= quantity} 写进 WHERE 而不是先查再改，是防超卖的关键：
     * 「查库存 → 判断 → 扣减」三步在并发下会互相踩脚，
     * 而数据库对单条 UPDATE 的行锁天然串行化，0 行受影响就说明被别人抢先扣光了。
     * 调用方必须检查返回值，为 0 时抛异常回滚整个下单事务。
     */
    @Update("UPDATE t_product_sku SET stock = stock - #{quantity}, update_time = now() "
            + "WHERE id = #{skuId} AND status = 1 AND stock >= #{quantity}")
    int deductStock(@Param("skuId") Long skuId, @Param("quantity") int quantity);

    /** 回补库存（取消订单 / 超时释放）。不做上限钳制：回补的永远是当初扣掉的。 */
    @Update("UPDATE t_product_sku SET stock = stock + #{quantity}, update_time = now() "
            + "WHERE id = #{skuId}")
    int restoreStock(@Param("skuId") Long skuId, @Param("quantity") int quantity);
}
