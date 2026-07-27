package com.bi.agent.bi.domain;

import com.bi.agent.common.BaseEntity;
import org.apache.commons.lang3.builder.ToStringBuilder;

/**
 * BI 大屏配置表 bi_dashboard
 */
public class BiDashboard extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /** 大屏ID */
    private Long id;

    /** 大屏名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 布局+图表+数据源配置JSON */
    private String configJson;

    /** 缩略图（base64） */
    private String thumbnail;

    /** 状态：0-停用，1-启用 */
    private String status;

    /** 是否公开：0-否，1-是 */
    private String isPublic;

    /** 公开访问令牌 */
    private String accessToken;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getConfigJson() {
        return configJson;
    }

    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }

    public String getThumbnail() {
        return thumbnail;
    }

    public void setThumbnail(String thumbnail) {
        this.thumbnail = thumbnail;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getIsPublic() {
        return isPublic;
    }

    public void setIsPublic(String isPublic) {
        this.isPublic = isPublic;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("id", getId())
                .append("name", getName())
                .append("description", getDescription())
                .append("status", getStatus())
                .append("isPublic", getIsPublic())
                .toString();
    }
}
