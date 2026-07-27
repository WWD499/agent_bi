package com.bi.agent.vo;

import java.util.List;

/**
 * 单条聊天消息 VO：角色 + 内容，前端气泡渲染用。
 */
public class ChatMessageVo {

    /** 角色：user / assistant */
    private String role;
    /** 消息正文 */
    private String content;
    /** 该条 assistant 消息携带的图表（ECharts option 列表）；仅 assistant 可能非空 */
    private List<Object> charts;

    public ChatMessageVo() {
    }

    public ChatMessageVo(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<Object> getCharts() {
        return charts;
    }

    public void setCharts(List<Object> charts) {
        this.charts = charts;
    }
}
