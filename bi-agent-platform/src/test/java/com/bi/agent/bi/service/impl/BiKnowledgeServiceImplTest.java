package com.bi.agent.bi.service.impl;

import com.bi.agent.bi.domain.BiKnowledge;
import com.bi.agent.bi.mapper.BiKnowledgeMapper;
import com.bi.agent.bi.service.llm.LlmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RAG 知识库纯逻辑单元测试（Phase 1 收口回归守卫）。
 *
 * <p>extractKeywords 为包级可见纯函数，直接测试；searchSimilar 的“向量优先 +
 * 关键词兜底”降级路径用 Mockito 模拟 Mapper / LlmService（不连数据库、不调真实网关）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BiKnowledgeServiceImplTest {

    @Mock
    private BiKnowledgeMapper knowledgeMapper;
    @Mock
    private LlmService llmService;
    @InjectMocks
    private BiKnowledgeServiceImpl service;

    private BiKnowledge sample;

    @BeforeEach
    void setUp() {
        sample = new BiKnowledge();
        sample.setTitle("销售额定义");
        sample.setContent("销售额指成交总额");
        // 关键词兜底检索始终返回这条样例知识
        when(knowledgeMapper.searchByKeyword(anyList(), anyInt(), any()))
                .thenReturn(List.of(sample));
        // 向量化不可用（模拟无 embedding 模型 / 网关异常）
        when(llmService.embed(anyList())).thenThrow(new RuntimeException("embedding unavailable"));
    }

    // ===================== extractKeywords（纯函数）=====================

    @Test
    void extractKeywords_removesStopwordsAndBiGramsChinese() {
        // “查询本月销售额是多少” → 去停用词 → “销售额” → 2-gram：销售 / 售额
        List<String> kw = service.extractKeywords("查询本月销售额是多少");
        assertThat(kw).containsExactly("销售", "售额");
    }

    @Test
    void extractKeywords_handlesLongChineseFragment() {
        // “库存预警规则怎么配置” → 库存 / 预警 / 规则 / 配置（7 个 2-gram）
        List<String> kw = service.extractKeywords("库存预警规则怎么配置");
        assertThat(kw).contains("库存", "预警", "规则", "配置");
        // 7 个汉字 → 2-gram 滑窗共 7-2+1 = 6 个
        assertThat(kw).hasSize(6);
    }

    @Test
    void extractKeywords_emptyWhenOnlyStopwords() {
        assertThat(service.extractKeywords("查询本月多少")).isEmpty();
    }

    @Test
    void extractKeywords_emptyForNull() {
        assertThat(service.extractKeywords(null)).isEmpty();
    }

    @Test
    void extractKeywords_preservesEnglishFragmentAsWhole() {
        // 英文无空格、非全汉字 → 整体作为一个关键词（既有行为）
        List<String> kw = service.extractKeywords("user sales report");
        assertThat(kw).hasSize(1);
        assertThat(kw.get(0)).isEqualTo("usersalesreport");
    }

    // ===================== searchSimilar 降级路径 =====================

    @Test
    void searchSimilar_usesKeywordWhenEmbeddingDisabled() {
        // embeddingEnabled 默认 false（无 Spring @Value 注入）→ 直接走关键词
        List<BiKnowledge> result = service.searchSimilar("销售额", 3, null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("销售额定义");
        verify(knowledgeMapper).searchByKeyword(anyList(), anyInt(), any());
        verify(llmService, never()).embed(anyList());
    }

    @Test
    void searchSimilar_fallsBackToKeywordWhenEmbeddingThrows() throws Exception {
        // 模拟“向量化可用但调用抛错”的真实降级场景（Phase 1 修复的关键路径）
        setEmbeddingEnabled(true);
        List<BiKnowledge> result = service.searchSimilar("销售额", 3, null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("销售额定义");
        verify(llmService).embed(anyList()); // 确实尝试过向量化
        verify(knowledgeMapper).searchByKeyword(anyList(), anyInt(), any());
    }

    private void setEmbeddingEnabled(boolean value) throws Exception {
        Field f = BiKnowledgeServiceImpl.class.getDeclaredField("embeddingEnabled");
        f.setAccessible(true);
        f.set(service, value);
    }
}
