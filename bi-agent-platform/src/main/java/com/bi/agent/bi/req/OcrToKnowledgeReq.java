package com.bi.agent.bi.req;

/**
 * OCR 结果写入 RAG 知识库请求。
 * 前端以 JSON body 形式提交 { recordId?, title, content }，与 /record 保持一致的 @RequestBody 约定。
 */
public class OcrToKnowledgeReq {

    /** 关联的 OCR 历史记录 id（可选，用于回写结构化结果） */
    private Long recordId;

    /** 知识标题（必填） */
    private String title;

    /** 知识正文（必填，通常是 OCR 全文或抽取出的 JSON） */
    private String content;

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
}
