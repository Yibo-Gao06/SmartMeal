package com.smartmeal.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartmeal.domain.entity.Cart;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** t_cart 购物车 */
@Mapper
public interface CartMapper extends BaseMapper<Cart> {

    /**
     * 加购：同一 (user, sku, plan) 已存在则累加数量，否则插入。
     *
     * <p>用 {@code INSERT ... ON DUPLICATE KEY UPDATE} 而不是「先查再插」，
     * 因为 t_cart 上有唯一键 {@code uk_user_sku_plan}：
     * 双开页面同时点加购时，先查再插会撞唯一键报 500，
     * 而 upsert 在数据库层天然合并成一条。
     *
     * <p>注意 create_time / update_time 手动写 now()：
     * 原生 SQL 不走 MyBatis-Plus 的 MetaObjectHandler 自动填充。
     */
    @Insert("INSERT INTO t_cart (user_id, sku_id, quantity, selected, plan_id, create_time, update_time) "
            + "VALUES (#{userId}, #{skuId}, #{quantity}, 1, #{planId}, now(), now()) "
            + "ON DUPLICATE KEY UPDATE quantity = quantity + VALUES(quantity), update_time = now()")
    int upsertQuantity(@Param("userId") Long userId, @Param("skuId") Long skuId,
                       @Param("quantity") int quantity, @Param("planId") long planId);
}
