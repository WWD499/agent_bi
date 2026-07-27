package com.bi.agent.bi.vo;

import java.util.List;

/**
 * 单个 OCR 文本块（含识别文本、置信度、四点包围框）。
 */
public class OcrBlockVo {

    /** 识别出的文本 */
    private String text;

    /** 置信度 0~1 */
    private Double confidence;

    /** 四点包围框 [[x1,y1],[x2,y2],[x3,y3],[x4,y4]] */
    private List<List<Integer>> bbox;

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public List<List<Integer>> getBbox() {
        return bbox;
    }

    public void setBbox(List<List<Integer>> bbox) {
        this.bbox = bbox;
    }
}
