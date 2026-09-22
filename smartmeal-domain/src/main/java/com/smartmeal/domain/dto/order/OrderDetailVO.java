package com.smartmeal.domain.dto.order;

import com.smartmeal.domain.entity.OrderItem;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 订单详情：主单 + 明细快照。 */
@Data
public class OrderDetailVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String orderNo;
    private String status;
    private String sourceType;
    private Long planId;

    private BigDecimal totalAmount;
    private BigDecimal freightAmount;
    private BigDecimal discountAmount;
    private BigDecimal payAmount;

    private String payType;
    private LocalDateTime payTime;
    private LocalDateTime cancelTime;
    private LocalDateTime createTime;
    private String remark;

    private List<OrderItem> items = new ArrayList<>();
}
