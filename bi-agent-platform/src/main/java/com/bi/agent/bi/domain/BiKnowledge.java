package com.bi.agent.bi.domain;

import java.time.LocalDateTime;

/**
 * RAG 知识库表 bi_knowledge 实体（Phase 1）。
 * <p>不依赖若依 BaseEntity，独立手写 getter/setter。
 * {@code contentVector} 在 Java 侧以字符串形式承载（格式 {@code "[0.1,0.2,...]"}），
 * 入库时由 Mapper 的 {@code CAST(? AS vector)} 转成 PG 的 {@code vector(1024)}。
 *
 * @author ruoyi-bi (ported to agent-bi)
 */
public class BiKnowledge {

    /** 知识ID */
    private Long id;

    /** 文档标题 */
    private String title;

    /** 文档内容（切片后） */
    private String content;

    /** 内容向量（BGE-M3：1024维，字符串形式） */
    private String contentVector;

    /** 来源类型：manual-手动录入、ocr-OCR识别、file-文件上传 */
    private String sourceType;

    /** 来源URL或文件路径 */
    private String sourceUrl;

    /** 业务领域（如：财务、销售、库存等） */
    private String businessDomain;

    /** 标签（逗号分隔） */
    private String tags;

    /** 切片序号（同一文档的多个切片） */
    private Integer chunkIndex;

    /** 总切片数 */
    private Integer totalChunks;

    /** 状态：0-停用，1-启用 */
    private Integer status;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;

    private String remark;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getContentVector() {
        return contentVector;
    }

    public void setContentVector(String contentVector) {
        this.contentVector = contentVector;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getBusinessDomain() {
        return businessDomain;
    }

    public void setBusinessDomain(String businessDomain) {
        this.businessDomain = businessDomain;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public Integer getTotalChunks() {
        return totalChunks;
    }

    public void setTotalChunks(Integer totalChunks) {
        this.totalChunks = totalChunks;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getCreateBy() {
        return createBy;
    }

    public void setCreateBy(String createBy) {
        this.createBy = createBy;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public String getUpdateBy() {
        return updateBy;
    }

    public void setUpdateBy(String updateBy) {
        this.updateBy = updateBy;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
