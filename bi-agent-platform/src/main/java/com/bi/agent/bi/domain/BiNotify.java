package com.bi.agent.bi.domain;

import java.time.LocalDateTime;

/**
 * 站内信 / 用户通知（bi_notify）。
 *
 * <p>独立实体（不继承 BaseEntity），手写 getter/setter，无 Lombok，与 BiAlertRecord 风格一致。
 * 预警触发后由 BiAlertNotifyServiceImpl 写入，替代原先「仅打日志」的站内信方案，
 * 前端通知中心（站内信）据此展示并支持「标记已读 / 全部已读」。
 */
public class BiNotify {

    private Long id;

    /** 接收人（Sa-Token 登录 id，即用户名） */
    private String userId;

    /** 关联预警规则 id（bi_alert_config.id） */
    private Long ruleId;

    /** 关联预警记录 id（bi_alert_record.id） */
    private Long recordId;

    /** 通知标题 */
    private String title;

    /** 通知正文 */
    private String content;

    /** 级别：info / warning / critical */
    private String level;

    /** 是否已读：0-未读，1-已读 */
    private Integer isRead;

    /** 创建时间 */
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Long getRuleId() {
        return ruleId;
    }

    public void setRuleId(Long ruleId) {
        this.ruleId = ruleId;
    }

    public Long getRecordId() {
        return recordId;
    }

    public void setRecordId(Long recordId) {
        this.recordId = recordId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public Integer getIsRead() {
        return isRead;
    }

    public void setIsRead(Integer isRead) {
        this.isRead = isRead;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
