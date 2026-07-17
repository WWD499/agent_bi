package com.bi.agent.vo;

import java.util.List;

/**
 * 会话详情 VO：点击历史列表中某会话后，加载其完整消息流。
 *
 * <p>messages 仅含 user + assistant 最终答案（与记忆粒度一致，不存 tool 中间过程）。
 */
public class SessionDetailVo {

    /** 会话 ID */
    private String sessionId;
    /** 会话标题 */
    private String title;
    /** 消息流（user / assistant 交替） */
    private List<ChatMessageVo> messages;

    public SessionDetailVo() {
    }

    public SessionDetailVo(String sessionId, String title, List<ChatMessageVo> messages) {
        this.sessionId = sessionId;
        this.title = title;
        this.messages = messages;
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

    public List<ChatMessageVo> getMessages() {
        return messages;
    }

    public void setMessages(List<ChatMessageVo> messages) {
        this.messages = messages;
    }
}
