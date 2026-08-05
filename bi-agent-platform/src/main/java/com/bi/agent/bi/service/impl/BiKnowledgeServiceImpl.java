package com.bi.agent.bi.service.impl;

import com.bi.agent.bi.domain.BiKnowledge;
import com.bi.agent.bi.mapper.BiKnowledgeMapper;
import com.bi.agent.bi.service.IBiKnowledgeService;
import com.bi.agent.bi.service.llm.LlmService;
import com.bi.agent.common.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RAG 知识库服务实现
 *
 * <p>核心功能：
 * 1. 文本切分（按段落，每段≤500字，重叠50字）
 * 2. 向量化（调用 LlmService.embed()，BGE-M3 模型，1024维）
 * 3. 向量存储（pgvector，{@code vector(1024)}）
 * 4. 相似度检索（向量优先 + 关键词 ILIKE 兜底）
 *
 * <p>向量检索走 PG 的 {@code <=>} 余弦距离运算符（ivfflat 索引加速），
 * 无 embedding 模型或检索为空时自动降级到关键词子串匹配，保证可用。
 *
 * @author ruoyi-bi (ported to agent-bi)
 */
@Service
public class BiKnowledgeServiceImpl implements IBiKnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(BiKnowledgeServiceImpl.class);

    /**
     * 文本切分配置
     */
    private static final int CHUNK_SIZE = 500;      // 每段最大字数
    private static final int CHUNK_OVERLAP = 50;   // 重叠字数
    private static final int EMBED_BATCH_SIZE = 16; // 每次向量化批量大小

    /**
     * 中文停用词（用于关键词兜底检索时过滤噪声）
     * 含多字词，提取时按长度降序优先匹配，避免 "本月" 被 "月" 误留
     */
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "的", "了", "吗", "呢", "吧", "啊", "是", "在", "我", "你", "他", "她", "它",
            "我们", "你们", "它们", "查询", "统计", "显示", "列出", "各个", "每个", "所有",
            "一共", "总共", "多少", "几个", "什么", "怎么", "如何", "哪些", "这个", "那个",
            "上月", "本月", "上个月", "去年", "今年", "时间", "日期", "数据",
            "信息", "情况", "分析", "一下", "请", "帮我", "想", "知道", "没有", "和",
            "与", "及", "或", "之", "等", "一个", "一条", "分别", "对比", "趋势", "变化"
    ));

    @Autowired
    private BiKnowledgeMapper knowledgeMapper;

    @Autowired
    private LlmService llmService;

    /**
     * 是否启用向量化（可在 application.yml 中配置，默认 true）
     * 设为 false 时，跳过向量化，仅存储文本内容
     */
    @Value("${ai.ark.embedding-enabled:true}")
    private boolean embeddingEnabled;

    // =====================================================
    // 新增 / 更新
    // =====================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertBiKnowledge(BiKnowledge knowledge) {
        // 文本切分
        List<String> chunks = splitText(knowledge.getContent());
        if (chunks.isEmpty()) {
            throw new BizException("知识内容不能为空");
        }

        // 批量向量化（如果启用且可用）
        List<float[]> embeddings = null;
        if (embeddingEnabled) {
            try {
                embeddings = embedTexts(chunks);
            } catch (Exception e) {
                log.error("向量化失败，将跳过向量存储", e);
            }
        }

        // 批量插入
        List<BiKnowledge> entities = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            BiKnowledge entity = new BiKnowledge();
            entity.setTitle(knowledge.getTitle());
            entity.setContent(chunks.get(i));
            if (embeddings != null && i < embeddings.size()) {
                entity.setContentVector(floatArrayToPgVector(embeddings.get(i)));
            }
            entity.setSourceType(knowledge.getSourceType());
            entity.setSourceUrl(knowledge.getSourceUrl());
            entity.setBusinessDomain(knowledge.getBusinessDomain());
            entity.setTags(knowledge.getTags());
            entity.setChunkIndex(i);
            entity.setTotalChunks(chunks.size());
            entity.setStatus(knowledge.getStatus() != null ? knowledge.getStatus() : 1);
            entity.setCreateBy(knowledge.getCreateBy());
            entity.setRemark(knowledge.getRemark());
            entities.add(entity);
        }

        int rows = knowledgeMapper.batchInsertBiKnowledge(entities);
        log.info("插入知识条目：{}，切片数：{}，向量化：{}", knowledge.getTitle(), chunks.size(), embeddings != null);
        return rows;
    }

    @Override
    public int batchInsertBiKnowledge(List<BiKnowledge> list) {
        // 批量向量化
        List<String> allTexts = new ArrayList<>();
        for (BiKnowledge k : list) {
            allTexts.add(k.getContent());
        }
        List<float[]> embeddings = embedTexts(allTexts);

        // 设置向量
        for (int i = 0; i < list.size(); i++) {
            list.get(i).setContentVector(floatArrayToPgVector(embeddings.get(i)));
        }

        return knowledgeMapper.batchInsertBiKnowledge(list);
    }

    @Override
    public BiKnowledge selectBiKnowledgeById(Long id) {
        return knowledgeMapper.selectBiKnowledgeById(id);
    }

    @Override
    public List<BiKnowledge> selectBiKnowledgeList(BiKnowledge knowledge) {
        return knowledgeMapper.selectBiKnowledgeList(knowledge);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateBiKnowledge(BiKnowledge knowledge) {
        // 如果内容有更新，重新向量化（如果启用）
        if (knowledge.getContent() != null && !knowledge.getContent().trim().isEmpty()
                && embeddingEnabled) {
            try {
                List<float[]> embeddings = embedTexts(Collections.singletonList(knowledge.getContent()));
                knowledge.setContentVector(floatArrayToPgVector(embeddings.get(0)));
            } catch (Exception e) {
                log.error("向量化失败，将保持原向量", e);
            }
        }
        return knowledgeMapper.updateBiKnowledge(knowledge);
    }

    @Override
    public int deleteBiKnowledgeById(Long id) {
        return knowledgeMapper.deleteBiKnowledgeById(id);
    }

    @Override
    public int deleteBiKnowledgeByIds(Long[] ids) {
        return knowledgeMapper.deleteBiKnowledgeByIds(ids);
    }

    // =====================================================
    // RAG 检索
    // =====================================================

    @Override
    public List<BiKnowledge> searchSimilar(String query, int topK, String domain) {
        if (query == null || query.trim().isEmpty()) {
            return new ArrayList<>();
        }

        // 1. 优先语义向量检索（embedding 模型可用时）
        if (embeddingEnabled) {
            try {
                List<float[]> queryEmbedding = embedTexts(Collections.singletonList(query.trim()));
                String vectorStr = floatArrayToPgVector(queryEmbedding.get(0));
                List<BiKnowledge> vectorResults = knowledgeMapper.searchByVector(vectorStr, topK, domain);
                if (vectorResults != null && !vectorResults.isEmpty()) {
                    return vectorResults;
                }
            } catch (Exception e) {
                log.error("向量相似度检索失败，转关键词兜底", e);
            }
        }

        // 2. 无向量 / 向量检索为空 → 关键词兜底检索（不依赖 embedding 模型）
        return keywordSearch(query, topK, domain);
    }

    @Override
    public String buildRagContext(String query, Long datasourceId) {
        // 检索最相似的5条知识（Phase1 暂不按 datasourceId 过滤业务领域）
        List<BiKnowledge> relevant = searchSimilar(query, 5, null);

        if (relevant == null || relevant.isEmpty()) {
            return "";
        }

        // 拼接成结构化上下文
        StringBuilder context = new StringBuilder();
        context.append("【业务知识库参考】\n");

        if (log.isDebugEnabled()) {
            String preview = context + relevant.stream()
                    .map(k -> (k.getTitle() != null ? "【" + k.getTitle() + "】" : "") + k.getContent())
                    .reduce((a, b) -> a + " | " + b).orElse("");
            log.debug("RAG 召回 {} 条知识，注入上下文（前240字）：{}",
                    relevant.size(), preview.length() > 240 ? preview.substring(0, 240) : preview);
        }
        for (int i = 0; i < relevant.size(); i++) {
            BiKnowledge k = relevant.get(i);
            context.append("\n").append(i + 1).append(". ");
            context.append(k.getTitle() != null ? "【" + k.getTitle() + "】" : "");
            context.append(k.getContent());
        }
        context.append("\n");

        return context.toString();
    }

    // =====================================================
    // 关键词兜底检索（不依赖 embedding 模型）
    // =====================================================

    /**
     * 关键词兜底检索（无 embedding 模型时可用）
     * 从查询中提取业务关键词，使用 PostgreSQL ILIKE 做子串匹配
     */
    private List<BiKnowledge> keywordSearch(String query, int topK, String domain) {
        List<String> terms = extractKeywords(query);
        if (terms.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return knowledgeMapper.searchByKeyword(terms, topK, domain);
        } catch (Exception e) {
            log.error("关键词检索失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 从自然语言查询中提取检索关键词
     * 策略：去除停用词（含多字词，按长度降序优先匹配）+ 去除标点空白，
     * 剩余片段作为关键词；中文长串按 2-gram 滑动切分（中文无空格，避免整串 ILIKE 匹配不到），
     * 英文/数字片段整体保留，最终过滤长度 < 2 的噪声
     */
    List<String> extractKeywords(String query) {
        if (query == null) {
            return new ArrayList<>();
        }
        // 去除标点符号与空白
        String text = query.replaceAll("[\\p{P}\\s]+", "");
        // 去除停用词（按长度降序，优先匹配多字词，避免 "本月" 被 "月" 误留）
        List<String> words = new ArrayList<>(STOP_WORDS);
        words.sort((a, b) -> b.length() - a.length());
        for (String w : words) {
            if (!w.isEmpty()) {
                text = text.replace(w, " ");
            }
        }
        // 剩余片段作为关键词；中文长串按 2-gram 滑动切分，提升 ILIKE 子串召回
        List<String> terms = new ArrayList<>();
        for (String frag : text.split("\\s+")) {
            frag = frag.trim();
            if (frag.isEmpty()) {
                continue;
            }
            if (frag.length() <= 2 || !frag.matches("^[\\p{IsHan}]+$")) {
                terms.add(frag);
            } else {
                for (int i = 0; i + 2 <= frag.length(); i++) {
                    terms.add(frag.substring(i, i + 2));
                }
            }
        }
        return terms;
    }

    // =====================================================
    // 向量化重新生成
    // =====================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reEmbed(Long id) {
        if (!embeddingEnabled) {
            throw new BizException("向量化未启用，无法重新向量化");
        }

        BiKnowledge knowledge = knowledgeMapper.selectBiKnowledgeById(id);
        if (knowledge == null) {
            throw new BizException("知识条目不存在");
        }

        List<float[]> embeddings = embedTexts(Collections.singletonList(knowledge.getContent()));
        knowledge.setContentVector(floatArrayToPgVector(embeddings.get(0)));
        knowledgeMapper.updateBiKnowledge(knowledge);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchReEmbed() {
        if (!embeddingEnabled) {
            throw new BizException("向量化未启用，无法重新向量化");
        }

        List<BiKnowledge> all = knowledgeMapper.selectBiKnowledgeList(new BiKnowledge());
        if (all.isEmpty()) {
            return;
        }

        // 分批向量化（避免单次请求过大）
        for (int i = 0; i < all.size(); i += EMBED_BATCH_SIZE) {
            int end = Math.min(i + EMBED_BATCH_SIZE, all.size());
            List<BiKnowledge> batch = all.subList(i, end);
            List<String> texts = new ArrayList<>();
            for (BiKnowledge k : batch) {
                texts.add(k.getContent());
            }

            List<float[]> embeddings = embedTexts(texts);

            for (int j = 0; j < batch.size(); j++) {
                batch.get(j).setContentVector(floatArrayToPgVector(embeddings.get(j)));
                knowledgeMapper.updateBiKnowledge(batch.get(j));
            }

            log.info("批量重新向量化进度：{}/{}", end, all.size());
        }
    }

    // =====================================================
    // 私有方法：文本切分 & 向量化
    // =====================================================

    /**
     * 文本切分（按段落，重叠切分）
     * 策略：
     * 1. 先按 \n\n 分段
     * 2. 每段超过 CHUNK_SIZE 时，按句子切分
     * 3. 最终每段不超过 CHUNK_SIZE，相邻段重叠 CHUNK_OVERLAP 字
     */
    private List<String> splitText(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return chunks;
        }

        text = text.trim();

        // 1. 按段落切分
        String[] paragraphs = text.split("\\n\\s*\\n");
        for (String para : paragraphs) {
            para = para.trim();
            if (para.isEmpty()) continue;
            if (para.length() <= CHUNK_SIZE) {
                chunks.add(para);
            } else {
                // 2. 长段落按句子切分
                List<String> sentences = splitBySentence(para);
                StringBuilder current = new StringBuilder();
                for (String sent : sentences) {
                    if (current.length() + sent.length() > CHUNK_SIZE) {
                        if (current.length() > 0) {
                            chunks.add(current.toString().trim());
                        }
                        // 重叠：保留最后 CHUNK_OVERLAP 字
                        String overlap = current.length() > CHUNK_OVERLAP
                                ? current.substring(current.length() - CHUNK_OVERLAP)
                                : current.toString();
                        current = new StringBuilder(overlap).append(sent);
                    } else {
                        current.append(sent);
                    }
                }

                if (current.length() > 0) {
                    chunks.add(current.toString().trim());
                }
            }
        }

        return chunks;
    }

    /**
     * 按句子切分（。！？\n）
     */
    private List<String> splitBySentence(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            if (c == '。' || c == '！' || c == '？' || c == '\n') {
                sentences.add(current.toString());
                current = new StringBuilder();
            }
        }
        if (current.length() > 0) {
            sentences.add(current.toString());
        }
        return sentences;
    }

    /**
     * 批量文本向量化
     * 调用 LlmService.embed()，自动处理分批
     */
    private List<float[]> embedTexts(List<String> texts) {
        List<float[]> result = new ArrayList<>();
        // 分批调用（避免单次请求过大）
        for (int i = 0; i < texts.size(); i += EMBED_BATCH_SIZE) {
            int end = Math.min(i + EMBED_BATCH_SIZE, texts.size());
            List<String> batch = texts.subList(i, end);
            try {
                List<float[]> batchResult = llmService.embed(batch);
                result.addAll(batchResult);
            } catch (Exception e) {
                log.error("向量化失败，批次：{}-{}", i, end, e);
                throw new BizException("向量化失败：" + e.getMessage());
            }
        }
        return result;
    }

    /**
     * float[] 转 pgvector 字符串格式
     * 输入：[0.1f, 0.2f, ..., 0.3f]
     * 输出："[0.1,0.2,...,0.3]"
     */
    private String floatArrayToPgVector(float[] vec) {
        if (vec == null || vec.length == 0) {
            return "[0.0]";
        }

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vec[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
