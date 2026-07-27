package com.bi.agent.bi.enums;

/**
 * 预警记录状态枚举（消灭 "pending" / "confirmed" / "resolved" 魔法字符串）
 *
 * @author ruoyi-bi (ported to agent-bi)
 */
public enum AlertStatus {
    PENDING("pending", "待处理"),
    CONFIRMED("confirmed", "已确认"),
    RESOLVED("resolved", "已解决");

    private final String code;
    private final String desc;

    AlertStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static AlertStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (AlertStatus s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        return null;
    }
}
