package com.bi.agent.controller.bi;

import com.bi.agent.bi.domain.BiOcrRecord;
import com.bi.agent.bi.req.OcrExtractReq;
import com.bi.agent.bi.req.OcrToKnowledgeReq;
import com.bi.agent.bi.service.IBiOcrService;
import com.bi.agent.bi.vo.OcrResultVo;
import com.bi.agent.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * OCR 控制器。
 *
 * <ul>
 *   <li>POST   /api/bi/ocr              — 上传图片识别（转发 PaddleOCR）</li>
 *   <li>POST   /api/bi/ocr/extract     — 对文本做结构化抽取</li>
 *   <li>POST   /api/bi/ocr/to-knowledge — 结果写入 RAG 知识库</li>
 *   <li>POST   /api/bi/ocr/record      — 新增历史记录</li>
 *   <li>GET    /api/bi/ocr/record/list  — 历史列表</li>
 *   <li>GET    /api/bi/ocr/record/{id}  — 历史详情</li>
 *   <li>DELETE /api/bi/ocr/record/{id}  — 删除历史</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/bi/ocr")
public class BiOcrController {

    private static final Logger log = LoggerFactory.getLogger(BiOcrController.class);

    @Autowired
    private IBiOcrService ocrService;

    @PostMapping
    public Result<OcrResultVo> ocr(@RequestParam("file") MultipartFile file) {
        OcrResultVo vo = ocrService.ocr(file);
        return Result.ok(vo);
    }

    @PostMapping("/extract")
    public Result<String> extract(@RequestBody OcrExtractReq req) {
        return Result.ok(ocrService.extract(req.getText(), req.getSchema()));
    }

    @PostMapping("/to-knowledge")
    public Result<Integer> toKnowledge(@RequestBody OcrToKnowledgeReq req) {
        return Result.ok(ocrService.saveToKnowledge(req.getRecordId(), req.getTitle(), req.getContent()));
    }

    @PostMapping("/record")
    public Result<Long> addRecord(@RequestBody BiOcrRecord record) {
        return Result.ok(ocrService.addRecord(record));
    }

    @GetMapping("/record/list")
    public Result<List<BiOcrRecord>> listRecords(@RequestParam(required = false) Long dsId,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return Result.ok(ocrService.listRecords(dsId, keyword, page, size));
    }

    @GetMapping("/record/{id}")
    public Result<BiOcrRecord> getRecord(@PathVariable Long id) {
        return Result.ok(ocrService.getRecord(id));
    }

    @DeleteMapping("/record/{id}")
    public Result<Integer> deleteRecord(@PathVariable Long id) {
        return Result.ok(ocrService.deleteRecord(id));
    }
}
