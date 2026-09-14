package com.smartmeal.common.exception;

import com.smartmeal.common.result.ResultCode;
import lombok.Getter;

import java.io.Serial;

/**
 * 业务异常。
 *
 * <p>约定：业务异常不打印堆栈（属预期分支），系统异常才打堆栈。
 * 详见 {@link GlobalExceptionHandler}。
 */
@Getter
public class BusinessException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Integer code;

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    /** 条件成立时抛出，用于替代大量 if-throw。 */
    public static void throwIf(boolean condition, ResultCode resultCode) {
        if (condition) {
            throw new BusinessException(resultCode);
        }
    }

    public static void throwIf(boolean condition, ResultCode resultCode, String message) {
        if (condition) {
            throw new BusinessException(resultCode, message);
        }
    }
}
