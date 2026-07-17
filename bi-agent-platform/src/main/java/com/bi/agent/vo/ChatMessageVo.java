package com.bi.agent.vo;

/**
 * 单条聊天消息 VO：角色 + 内容，前端气泡渲染用。
 */
public class ChatMessageVo {

    /** 角色：user / assistant */
    private String role;
    /** 消息正文 */
    private String content;

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
}
