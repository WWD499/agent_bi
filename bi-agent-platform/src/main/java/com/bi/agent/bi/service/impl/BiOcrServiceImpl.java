package com.bi.agent.bi.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bi.agent.bi.domain.BiKnowledge;
import com.bi.agent.bi.domain.BiOcrRecord;
import com.bi.agent.bi.mapper.BiOcrRecordMapper;
import com.bi.agent.bi.service.IBiKnowledgeService;
import com.bi.agent.bi.service.IBiOcrService;
import com.bi.agent.bi.service.llm.LlmService;
import com.bi.agent.bi.vo.OcrBlockVo;
import com.bi.agent.bi.vo.OcrResultVo;
import com.bi.agent.common.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * OCR 业务实现。
 *
 * <p>识别：转发图片到本地 PaddleOCR FastAPI 服务（可配置 {@code ocr.paddleocr-url}，
 * 默认 http://localhost:8866），并兼容多种返回格式归一化为 {@link OcrResultVo}。
 * <p>抽取：复用 {@link LlmService} 调大模型把 OCR 全文抽成结构化 JSON。
 * <p>入库：复用 RAG 知识库的 {@code insertBiKnowledge}（自动切分 + 向量化）。
 */
@Service
public class BiOcrServiceImpl implements IBiOcrService {

    private static final Logger log = LoggerFactory.getLogger(BiOcrServiceImpl.class);

    @Value("${ocr.paddleocr-url:http://localhost:8866}")
    private String paddleUrl;

    @Autowired
    private LlmService llmService;

    @Autowired
    private IBiKnowledgeService knowledgeService;

    @Autowired
    private BiOcrRecordMapper ocrRecordMapper;

    @Override
    public OcrResultVo ocr(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请上传图片文件");
        }
        try {
            // 本地 PaddleOCR 服务：首次加载模型可能耗时较久，单独设置较长超时避免无限挂起
            SimpleClientHttpRequestFactory ocrFactory = new SimpleClientHttpRequestFactory();
            ocrFactory.setConnectTimeout(Duration.ofMillis(10000));
            ocrFactory.setReadTimeout(Duration.ofMillis(120000));
            RestTemplate rt = new RestTemplate(ocrFactory);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            ByteArrayResource res = new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return file.getOriginalFilename() != null ? file.getOriginalFilename() : "image.png";
                }
            };
            body.add("file", res);
            HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> resp = rt.postForEntity(paddleUrl + "/ocr", entity, String.class);
            if (resp.getStatusCode() != HttpStatus.OK || resp.getBody() == null) {
                throw new BizException("OCR 服务返回异常：" + resp.getStatusCode());
            }
            return parse(resp.getBody());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用 OCR 服务失败，url={}", paddleUrl, e);
            throw new BizException("调用 OCR 服务失败（" + paddleUrl + "）：" + e.getMessage());
        }
    }

    private OcrResultVo parse(String raw) {
        OcrResultVo vo = new OcrResultVo();
        vo.setRaw(raw);
        List<OcrBlockVo> blocks = new ArrayList<>();
        JSONArray arr;
        try {
            JSONObject root = JSON.parseObject(raw);
            if (root.containsKey("data")) arr = root.getJSONArray("data");
            else if (root.containsKey("result")) arr = root.getJSONArray("result");
            else if (root.containsKey("results")) arr = root.getJSONArray("results");
            else if (root.containsKey("ocr_result")) arr = root.getJSONArray("ocr_result");
            else if (root.containsKey("texts")) arr = root.getJSONArray("texts");
            else arr = new JSONArray();
        } catch (Exception e) {
            try {
                arr = JSON.parseArray(raw);
            } catch (Exception e2) {
                arr = new JSONArray();
            }
        }
        StringBuilder sb = new StringBuilder();
        if (arr != null) {
            for (int i = 0; i < arr.size(); i++) {
                OcrBlockVo b = parseBlock(arr.get(i));
                if (b != null) {
                    blocks.add(b);
                    if (b.getText() != null && !b.getText().isEmpty()) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(b.getText());
                    }
                }
            }
        }
        vo.setBlocks(blocks);
        vo.setText(sb.toString());
        return vo;
    }

    private OcrBlockVo parseBlock(Object el) {
        if (el instanceof JSONObject) {
            JSONObject o = (JSONObject) el;
            OcrBlockVo b = new OcrBlockVo();
            if (o.containsKey("text")) b.setText(o.getString("text"));
            else if (o.containsKey("transcription")) b.setText(o.getString("transcription"));
            else if (o.containsKey("rec_text")) b.setText(o.getString("rec_text"));
            else if (o.containsKey("content")) b.setText(o.getString("content"));
            if (o.containsKey("confidence")) b.setConfidence(o.getDouble("confidence"));
            else if (o.containsKey("score")) b.setConfidence(o.getDouble("score"));
            else if (o.containsKey("conf")) b.setConfidence(o.getDouble("conf"));
            if (o.containsKey("bbox")) b.setBbox(toIntMatrix(o.getJSONArray("bbox")));
            else if (o.containsKey("points")) b.setBbox(toIntMatrix(o.getJSONArray("points")));
            else if (o.containsKey("box")) b.setBbox(toIntMatrix(o.getJSONArray("box")));
            return b;
        } else if (el instanceof JSONArray) {
            // PaddleOCR 官方格式：[[bbox], ["text", conf]]
            JSONArray a = (JSONArray) el;
            if (a.size() >= 2) {
                OcrBlockVo b = new OcrBlockVo();
                b.setBbox(toIntMatrix(a.getJSONArray(0)));
                Object inner = a.get(1);
                if (inner instanceof JSONArray) {
                    JSONArray ia = (JSONArray) inner;
                    if (ia.size() >= 1) b.setText(ia.getString(0));
                    if (ia.size() >= 2) b.setConfidence(ia.getDouble(1));
                } else if (inner instanceof String) {
                    b.setText((String) inner);
                }
                return b;
            }
        }
        return null;
    }

    private List<List<Integer>> toIntMatrix(JSONArray a) {
        if (a == null) return null;
        List<List<Integer>> m = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            Object row = a.get(i);
            List<Integer> r = new ArrayList<>();
            if (row instanceof JSONArray) {
                JSONArray ra = (JSONArray) row;
                for (int j = 0; j < ra.size(); j++) r.add(ra.getIntValue(j));
            }
            m.add(r);
        }
        return m;
    }

    @Override
    public String extract(String text, String schema) {
        if (text == null || text.trim().isEmpty()) {
            throw new BizException("提取内容不能为空");
        }
        String ans = llmService.chat(buildExtractPrompt(text, schema), 0.1);
        ans = ans.trim();
        if (ans.startsWith("```")) {
            int s = ans.indexOf('\n');
            int e = ans.lastIndexOf("```");
            if (s > 0 && e > s) ans = ans.substring(s + 1, e).trim();
        }
        try {
            JSON.parse(ans);
        } catch (Exception e) {
            throw new BizException("大模型未返回合法 JSON，请重试或调整抽取要求");
        }
        return ans;
    }

    private String buildExtractPrompt(String text, String schema) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个数据抽取引擎。下面是一段 OCR 识别出的文档文本。\n");
        sb.append("请从中抽取结构化字段，仅输出一个 JSON 对象（不要任何解释、不要 markdown 代码块包裹）。\n");
        if (schema != null && !schema.trim().isEmpty()) {
            sb.append("需要抽取的字段及说明：\n").append(schema.trim()).append("\n");
        } else {
            sb.append("请尽可能抽取关键字段（如标题、日期、金额、数量、主体、表格行等），字段名用中文或英文均可。\n");
        }
        sb.append("待抽取文本：\n```\n").append(text).append("\n```\n");
        return sb.toString();
    }

    @Override
    public int saveToKnowledge(Long recordId, String title, String content) {
        if (content == null || content.trim().isEmpty()) {
            throw new BizException("写入知识库的内容不能为空");
        }
        BiKnowledge k = new BiKnowledge();
        k.setTitle(title != null && !title.isEmpty() ? title : "OCR 识别 " + new Date());
        k.setContent(content.trim());
        k.setSourceType("ocr");
        k.setBusinessDomain("ocr");
        return knowledgeService.insertBiKnowledge(k);
    }

    @Override
    public Long addRecord(BiOcrRecord record) {
        if (record.getSource() == null) record.setSource("upload");
        ocrRecordMapper.insert(record);
        return record.getId();
    }

    @Override
    public List<BiOcrRecord> listRecords(Long dsId, String keyword, int page, int size) {
        int limit = size > 0 ? size : 20;
        int offset = (page > 0 ? page - 1 : 0) * limit;
        return ocrRecordMapper.selectList(dsId, keyword, limit, offset);
    }

    @Override
    public BiOcrRecord getRecord(Long id) {
        return ocrRecordMapper.selectById(id);
    }

    @Override
    public int deleteRecord(Long id) {
        return ocrRecordMapper.deleteById(id);
    }
}
