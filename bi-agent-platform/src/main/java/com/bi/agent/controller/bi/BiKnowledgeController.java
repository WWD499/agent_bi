package com.bi.agent.controller.bi;

import com.bi.agent.bi.domain.BiKnowledge;
import com.bi.agent.bi.service.IBiKnowledgeService;
import com.bi.agent.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * RAG 知识库管理控制器（Phase 1）。
 *
 * <p>鉴权：命中 Sa-Token 的 {@code /api/**} 规则，需登录态（token 放 {@code Authorization} 头）。
 *
 * <p>能力：
 * <ul>
 *   <li>POST   /api/bi/knowledge        — 新增知识（自动切分 + BGE-M3 向量化入库）</li>
 *   <li>GET    /api/bi/knowledge/list   — 条件查询知识列表</li>
 *   <li>POST   /api/bi/knowledge/search — 相似度检索（向量优先，关键词兜底）</li>
 *   <li>DELETE /api/bi/knowledge/{id}  — 删除单条知识</li>
 * </ul>
 *
 * @author agent-bi
 */
@RestController
@RequestMapping("/api/bi/knowledge")
public class BiKnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(BiKnowledgeController.class);

    @Autowired
    private IBiKnowledgeService knowledgeService;

    /**
     * 新增知识条目（自动切分 + 向量化）
     */
    @PostMapping
    public Result<Integer> add(@RequestBody BiKnowledge knowledge) {
        if (knowledge == null || knowledge.getContent() == null || knowledge.getContent().trim().isEmpty()) {
            return Result.fail(400, "content 不能为空");
        }
        log.info("新增知识条目：title={}, domain={}", knowledge.getTitle(), knowledge.getBusinessDomain());
        int rows = knowledgeService.insertBiKnowledge(knowledge);
        return Result.ok(rows);
    }

    /**
     * 知识列表（带条件：title / sourceType / businessDomain / status）
     */
    @GetMapping("/list")
    public Result<List<BiKnowledge>> list(BiKnowledge query) {
        return Result.ok(knowledgeService.selectBiKnowledgeList(query));
    }

    /**
     * 相似度检索（供前端 / 调试使用；NL2SQL 主流程内部也走同一检索）
     *
     * @param q     用户查询（必填）
     * @param topK  返回条数（默认 5）
     * @param domain 业务领域过滤（可选）
     */
    @PostMapping("/search")
    public Result<List<BiKnowledge>> search(@RequestParam String q,
                                            @RequestParam(defaultValue = "5") int topK,
                                            @RequestParam(required = false) String domain) {
        if (q == null || q.trim().isEmpty()) {
            return Result.fail(400, "q 不能为空");
        }
        return Result.ok(knowledgeService.searchSimilar(q.trim(), topK, domain));
    }

    /**
     * 删除单条知识
     */
    @DeleteMapping("/{id}")
    public Result<Integer> delete(@PathVariable Long id) {
        return Result.ok(knowledgeService.deleteBiKnowledgeById(id));
    }
}
