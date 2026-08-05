package com.bi.agent.bi.enums;

/**
 * 启用标志（消灭 0/1 魔法数字）。
 * 用于 BiAlertRule 的 status / analysisEnabled 等整型开关字段。
 */
public enum EnableFlag {
    DISABLED(0),
    ENABLED(1);

    private final int code;

    EnableFlag(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
