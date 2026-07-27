package com.bi.agent.common;

/**
 * 业务异常：携带错误码，统一由 GlobalExceptionHandler 转成 Result。
 * 区别于未预期的编程错误（如 NPE） —— 后者不带业务语义，走 500 兜底。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String msg) {
        super(msg);
        this.code = 500;
    }

    public BizException(int code, String msg) {
        super(msg);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
