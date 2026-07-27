package com.bi.agent.bi.domain;

import java.util.Date;

/**
 * OCR 识别历史记录（落库后可在页面回看）。
 */
public class BiOcrRecord {

    private Long id;

    /** 关联数据源 id（可选，写入业务库时用） */
    private Long dsId;

    /** 原图存储路径/URL（可选） */
    private String imagePath;

    /** 识别全文 */
    private String rawText;

    /** 大模型结构化抽取结果（JSON 字符串） */
    private String structuredJson;

    /** 来源：upload / agent 等 */
    private String source;

    /** 创建人 */
    private String createBy;

    /** 创建时间 */
    private Date createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDsId() {
        return dsId;
    }

    public void setDsId(Long dsId) {
        this.dsId = dsId;
    }

    public String getImagePath() {
        return imagePath;
    }

    public void setImagePath(String imagePath) {
        this.imagePath = imagePath;
    }

    public String getRawText() {
        return rawText;
    }

    public void setRawText(String rawText) {
        this.rawText = rawText;
    }

    public String getStructuredJson() {
        return structuredJson;
    }

    public void setStructuredJson(String structuredJson) {
        this.structuredJson = structuredJson;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getCreateBy() {
        return createBy;
    }

    public void setCreateBy(String createBy) {
        this.createBy = createBy;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }
}
