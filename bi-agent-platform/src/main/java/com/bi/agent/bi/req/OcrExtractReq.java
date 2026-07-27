package com.bi.agent.bi.req;

/**
 * OCR 结构化抽取请求。
 * 前端以 JSON body 形式提交 { text, schema? }，与 /record 保持一致的 @RequestBody 约定。
 */
public class OcrExtractReq {

    /** OCR 识别出的全文（必填） */
    private String text;

    /** 期望抽取的字段说明（可选） */
    private String schema;

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }
}
