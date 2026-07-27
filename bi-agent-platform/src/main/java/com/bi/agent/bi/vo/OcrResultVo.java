package com.bi.agent.bi.vo;

import java.util.List;

/**
 * OCR 识别结果（归一化后的统一结构，屏蔽不同 OCR 引擎返回差异）。
 */
public class OcrResultVo {

    /** 全文（所有文本块按阅读顺序拼接） */
    private String text;

    /** 文本块明细（含置信度、包围框） */
    private List<OcrBlockVo> blocks;

    /** OCR 服务返回的原始 JSON（便于前端/调试核对） */
    private String raw;

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public List<OcrBlockVo> getBlocks() {
        return blocks;
    }

    public void setBlocks(List<OcrBlockVo> blocks) {
        this.blocks = blocks;
    }

    public String getRaw() {
        return raw;
    }

    public void setRaw(String raw) {
        this.raw = raw;
    }
}
