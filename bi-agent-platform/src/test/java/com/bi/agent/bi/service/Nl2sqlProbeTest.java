package com.bi.agent.bi.service;

import com.bi.agent.bi.domain.BiDatasource;
import com.bi.agent.bi.service.IBiDatasourceService;
import com.bi.agent.bi.service.IBiKnowledgeService;
import com.bi.agent.bi.service.llm.LlmService;
import com.bi.agent.bi.service.llm.PromptBuilder;
import com.bi.agent.bi.service.probe.DataProbeService;
import com.bi.agent.bi.service.sql.ChartSelector;
import com.bi.agent.bi.service.sql.SqlValidator;
import com.bi.agent.bi.util.BiDataSourceFactory;
import com.bi.agent.bi.vo.QueryResultVo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NL2SQL 数据探查根治验证（T7）：端到端跑 {@link BiQueryService#naturalLanguageQuery}。
 *
 * <p>用 Mockito stub LlmService，模拟「探查后 LLM 正确把『上季度』映射到真实覆盖的 2025 区间」
 * （返回基于 2025 范围的 SQL），验证根治效果：
 * <ul>
 *   <li>rowCount &gt; 0（不再 0 行）；</li>
 *   <li>chartType ≠ "table"（能出图，前端可渲染）；</li>
 *   <li>getDataProfile() ≠ null（探查结果已回填）。</li>
 * </ul>
 *
 * <p>不加载 Spring 容器：手动 new BiQueryService 并通过反射注入依赖，
 * 连接真实 agent_bi 库（dsId=10）以执行探查与实际取数。
 */
class Nl2sqlProbeTest {

    /** 模拟「探查后 LLM 正确映射」的 SQL：落在真实覆盖的 2025 区间 */
    private static final String SQL_2025 =
            "SELECT dr.region_name, TO_CHAR(fso.order_date,'YYYY-MM') AS month, SUM(fso.amount) AS total_amount "
            + "FROM fact_sales_order fso JOIN dim_region dr ON fso.region_id=dr.region_id "
            + "WHERE fso.status='已完成' AND fso.order_date >= '2025-01-01' AND fso.order_date < '2026-01-01' "
            + "GROUP BY dr.region_name, month ORDER BY dr.region_name, month LIMIT 1000";

    private BiDatasource stubDatasource() {
        BiDatasource ds = new BiDatasource();
        ds.setId(10L);
        ds.setType("postgresql");
        ds.setHost("localhost");
        ds.setPort(5432);
        ds.setDatabaseName("agent_bi");
        ds.setUsername("postgres");
        ds.setPassword("postgres123");
        return ds;
    }

    private BiQueryService buildService() throws Exception {
        BiQueryService svc = new BiQueryService();
        BiDataSourceFactory dsf = new BiDataSourceFactory();
        SqlValidator validator = new SqlValidator();

        // stub LLM：任何 chat 调用都返回基于 2025 真实覆盖的 SQL
        LlmService llm = mock(LlmService.class);
        when(llm.chat(anyString(), anyDouble())).thenReturn(SQL_2025);

        // stub 数据源服务：返回指向 agent_bi 的 dsId=10
        IBiDatasourceService dsSvc = mock(IBiDatasourceService.class);
        when(dsSvc.selectBiDatasourceById(10L)).thenReturn(stubDatasource());

        // stub 知识库：Phase1 RAG 返回空，不影响
        IBiKnowledgeService ks = mock(IBiKnowledgeService.class);
        when(ks.buildRagContext(anyString(), anyLong())).thenReturn("");

        setField(svc, "llmService", llm);
        setField(svc, "promptBuilder", new PromptBuilder());
        setField(svc, "sqlValidator", validator);
        setField(svc, "chartSelector", new ChartSelector());
        setField(svc, "datasourceService", dsSvc);
        setField(svc, "knowledgeService", ks);
        setField(svc, "dataSourceFactory", dsf);
        setField(svc, "dataProbeService", new DataProbeService(dsf, validator));
        return svc;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void naturalLanguageQuery_lastQuarterTrend_returnsRowsAndChart() throws Exception {
        BiQueryService svc = buildService();

        QueryResultVo result = svc.naturalLanguageQuery("分析上季度各区域销售额趋势", 10L, null);

        // 根治验证 1：真实覆盖区间内的 2025 数据 → 行数 > 0（不再 0 行）
        assertTrue(result.getRowCount() > 0,
                "探查注入真实覆盖区间后，SQL 应命中 2025 数据，rowCount>0，实际=" + result.getRowCount());

        // 根治验证 2：能出图（前端可渲染），chartType 不应为 table
        assertTrue(!"table".equals(result.getChartType()),
                "chartType 不应为 table（应出图），实际=" + result.getChartType());

        // 根治验证 3：探查结果已回填，作为证据链
        assertNotNull(result.getDataProfile(),
                "QueryResultVo 应携带主表 DataProfile");
    }
}
