package com.smartmeal.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 订单 t_order。表名必须显式声明，{@code order} 是 SQL 保留字。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_order")
public class Order extends BaseEntity {

    /** 业务订单号，对外暴露；主键 id 不外泄，避免被遍历。 */
    private String orderNo;

    private Long userId;
    private Long planId;
    /** AI_PLAN / NORMAL，用于统计 AI 转化率。 */
    private String sourceType;

    private BigDecimal totalAmount;
    private BigDecimal payAmount;
    private BigDecimal freightAmount;
    private BigDecimal discountAmount;

    private String status;
    private String payType;
    private LocalDateTime payTime;
    private LocalDateTime deliveryTime;
    private LocalDateTime finishTime;
    private LocalDateTime cancelTime;
    private String remark;
}
