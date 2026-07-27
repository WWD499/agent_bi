package com.bi.agent.common;

/**
 * 统一 API 返回体（替代若依 AjaxResult）。
 * 使用 Java 21 record —— 不可变、简洁，面试可聊的现代语法点。
 *
 * @param <T> 业务数据载荷
 */
public record Result<T>(int code, String msg, T data) {

    /** 成功：带数据 */
    public static <T> Result<T> ok(T data) {
        return new Result<>(200, "ok", data);
    }

    /** 成功：无数据 */
    public static <T> Result<T> ok() {
        return new Result<>(200, "ok", null);
    }

    /** 失败：带错误码与消息 */
    public static <T> Result<T> fail(int code, String msg) {
        return new Result<>(code, msg, null);
    }
}
