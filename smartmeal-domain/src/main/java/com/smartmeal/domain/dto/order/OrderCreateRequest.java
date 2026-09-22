package com.smartmeal.domain.dto.order;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 下单请求：对勾选的购物车条目结算。 */
@Data
public class OrderCreateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Size(max = 200, message = "备注不能超过 200 字")
    private String remark;
}
