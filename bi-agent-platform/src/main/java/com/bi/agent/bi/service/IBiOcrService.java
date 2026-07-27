package com.bi.agent.bi.service;

import com.bi.agent.bi.domain.BiOcrRecord;
import com.bi.agent.bi.vo.OcrResultVo;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * OCR 业务服务：图片识别、结构化抽取、结果入 RAG 知识库、历史落库。
 */
public interface IBiOcrService {

    /** 调用 PaddleOCR 服务识别图片，归一化返回 */
    OcrResultVo ocr(MultipartFile file);

    /** 调用大模型对 OCR 全文做结构化抽取，返回 JSON 字符串 */
    String extract(String text, String schema);

    /** 将识别/抽取结果写入 RAG 知识库（自动切分 + BGE-M3 向量化） */
    int saveToKnowledge(Long recordId, String title, String content);

    /** 新增一条 OCR 历史记录，返回自增 id */
    Long addRecord(BiOcrRecord record);

    /** 分页查询历史记录（按数据源 / 关键词） */
    List<BiOcrRecord> listRecords(Long dsId, String keyword, int page, int size);

    /** 查询单条历史 */
    BiOcrRecord getRecord(Long id);

    /** 删除历史 */
    int deleteRecord(Long id);
}
