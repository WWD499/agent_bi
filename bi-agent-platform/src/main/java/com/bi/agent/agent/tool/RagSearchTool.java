package com.bi.agent.agent.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bi.agent.bi.domain.BiKnowledge;
import com.bi.agent.bi.service.IBiKnowledgeService;

import java.util.List;

/**
 * Agent 工具：RAG 语义检索（手写 ReAct 版）
 *
 * <p>包装现有 {@link IBiKnowledgeService#searchSimilar(String, int, String)}，
 * 在业务知识库（PG pgvector + BAAI/bge-m3）中做语义检索，
 * 返回与问题相关的业务口径、指标定义、历史查询经验等背景知识，
 * 供模型在 NL2SQL / 解读时补充领域上下文。
 */
public class RagSearchTool implements AgentTool {

    private final IBiKnowledgeService knowledgeService;

    public RagSearchTool(IBiKnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @Override
    public String name() {
        return "rag_search";
    }

    @Override
    public String description() {
        return "在业务知识库中做语义检索（RAG），返回与问题相关的业务口径、指标定义、"
                + "历史查询经验等背景知识。当用户提到专业术语、需要业务口径解释、"
                + "或 NL2SQL 之前想补充领域知识时调用。";
    }

    @Override
    public String jsonSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"query\":{\"type\":\"string\",\"description\":\"检索问题，例如『销售额口径』\"},"
                + "\"topK\":{\"type\":\"integer\",\"description\":\"返回最相似的条数，默认 5\"}},"
                + "\"required\":[\"query\"]}";
    }

    @Override
    public String call(String argsJson) {
        String q = "";
        int topK = 5;
        try {
            JSONObject a = JSON.parseObject(argsJson);
            q = a.getString("query");
            if (a.containsKey("topK")) {
                topK = a.getIntValue("topK");
            }
        } catch (Exception e) {
            return "参数解析失败：" + e.getMessage();
        }
        if (q == null || q.isBlank()) {
            return "缺少 query 参数";
        }
        try {
            List<BiKnowledge> list = knowledgeService.searchSimilar(q, topK, null);
            JSONArray arr = new JSONArray();
            for (BiKnowledge k : list) {
                JSONObject o = new JSONObject();
                o.put("id", k.getId());
                o.put("title", k.getTitle());
                o.put("content", k.getContent());
                o.put("businessDomain", k.getBusinessDomain());
                arr.add(o);
            }
            JSONObject out = new JSONObject();
            out.put("hitCount", list.size());
            out.put("knowledge", arr);
            return out.toJSONString();
        } catch (Exception e) {
            return "检索失败：" + e.getMessage();
        }
    }
}
