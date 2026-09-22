package com.smartmeal.domain.dto.order;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 支付请求。当前为模拟支付，payType 仅记录渠道来源。 */
@Data
public class OrderPayRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Size(max = 32)
    private String payType = "MOCK";
}
