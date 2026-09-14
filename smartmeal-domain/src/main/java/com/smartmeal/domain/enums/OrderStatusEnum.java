package com.smartmeal.domain.enums;

import lombok.Getter;

/** 订单状态。 */
@Getter
public enum OrderStatusEnum {

    PENDING_PAYMENT("PENDING_PAYMENT", "待支付"),
    PAID("PAID", "已支付"),
    DELIVERING("DELIVERING", "配送中"),
    COMPLETED("COMPLETED", "已完成"),
    CANCELLED("CANCELLED", "已取消"),
    REFUNDING("REFUNDING", "退款中"),
    REFUNDED("REFUNDED", "已退款");

    private final String code;
    private final String label;

    OrderStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static OrderStatusEnum of(String code) {
        for (OrderStatusEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
