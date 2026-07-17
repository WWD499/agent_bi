package com.bi.agent.vo;

/**
 * 会话摘要 VO：历史列表单条展示用。
 *
 * <p>字段与 AgentMemory 写入 Redis Hash 的元信息一一对齐，
 * 前端历史抽屉按此渲染「标题 / 预览 / 时间 / 消息数」。
 */
public class SessionSummaryVo {

    /** 会话 ID */
    private String sessionId;
    /** 会话标题（首条 user 提问去换行、截断 20 字 + …） */
    private String title;
    /** 最后一条 assistant 预览（截断 50 字） */
    private String preview;
    /** 创建时间（ms） */
    private Long createTime;
    /** 最后活跃时间（ms） */
    private Long lastActiveTime;
    /** 消息条数 */
    private int messageCount;

    public SessionSummaryVo() {
    }

    public SessionSummaryVo(String sessionId, String title, String preview,
                            Long createTime, Long lastActiveTime, int messageCount) {
        this.sessionId = sessionId;
        this.title = title;
        this.preview = preview;
        this.createTime = createTime;
        this.lastActiveTime = lastActiveTime;
        this.messageCount = messageCount;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getPreview() {
        return preview;
    }

    public void setPreview(String preview) {
        this.preview = preview;
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }

    public Long getLastActiveTime() {
        return lastActiveTime;
    }

    public void setLastActiveTime(Long lastActiveTime) {
        this.lastActiveTime = lastActiveTime;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(int messageCount) {
        this.messageCount = messageCount;
    }
}
