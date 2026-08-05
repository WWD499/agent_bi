package com.bi.agent.bi.service.llm;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM 网关冒烟测试（OCR / 对话 / RAG 共用的 OpenAI 兼容网关）。
 *
 * <p><b>默认不会运行</b>：通过系统属性 {@code -Dllm.smoke=true} 开启，避免每次 {@code mvn test}
 * 都走真实网络。手动运行命令（项目根目录）：
 * <pre>
 *   bash mvn test -Dllm.smoke=true -Dtest=LlmServiceConnectivityTest
 * </pre>
 * 依赖 {@code application.yml} 中的 {@code ai.ark.api-key} 与 {@code base-url}。
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "llm.smoke", matches = "true")
class LlmServiceConnectivityTest {

    @Autowired
    private LlmService llmService;

    @Test
    void gatewayChat_isReachable() {
        boolean ok = llmService.testConnection();
        assertTrue(ok, "LLM 网关 /chat/completions 不可达（检查 ai.ark.api-key / base-url / 网络）");
    }

    @Test
    void gatewayEmbeddings_isReachable_andDimensionMatches() {
        List<float[]> vectors = llmService.embed(List.of("冒烟测试文本"));
        assertNotNull(vectors);
        assertFalse(vectors.isEmpty());
        // BAAI/bge-m3 向量维度应为 1024（与 bi_knowledge.content_vector vector(1024) 对齐）
        assertEquals(1024, vectors.get(0).length, "Embedding 维度应与 bge-m3 的 1024 一致");
    }
}
