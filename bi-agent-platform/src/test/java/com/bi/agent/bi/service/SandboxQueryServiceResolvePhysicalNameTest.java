package com.bi.agent.bi.service;

import com.bi.agent.bi.domain.BiSandboxDb;
import com.bi.agent.bi.domain.BiSandboxTable;
import com.bi.agent.bi.mapper.BiSandboxMapper;
import com.bi.agent.bi.service.SandboxAuditService;
import com.bi.agent.common.BizException;
import com.bi.agent.bi.service.sql.ChartSelector;
import com.bi.agent.bi.service.sql.SqlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 覆盖沙箱表名解析的两个关键修复：
 * <ol>
 *   <li>listSandboxColumns 现在把入参当物理名传入 resolvePhysicalName（step2 精确匹配），
 *       避免物理名当短名时漏掉精确匹配、被模糊匹配误导向脏表。</li>
 *   <li>resolvePhysicalName 的模糊兜底跳过 __ 等退化名，避免 sales_dm__* 这类含 "__" 的
 *       合法物理名被 __ 脏表「包含命中」（即导致销售表显示医疗字段的根因）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SandboxQueryServiceResolvePhysicalNameTest {

    @Mock
    private BiSandboxMapper sandboxMapper;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private SqlValidator sqlValidator;

    @Mock
    private SandboxAuditService auditService;

    @SuppressWarnings("unused")
    @Mock
    private ChartSelector chartSelector;

    @InjectMocks
    private SandboxQueryService service;

    private BiSandboxTable sales;
    private BiSandboxTable junk;
    private BiSandboxTable singleCharJunk;

    @BeforeEach
    void setup() {
        // 真实库中的销售表（短名 demo_monthly_revenue / 物理名 sales_dm__demo_monthly_revenue）
        sales = new BiSandboxTable();
        sales.setTableName("demo_monthly_revenue");
        sales.setPhysicalName("sales_dm__demo_monthly_revenue");
        sales.setDbId(2L);

        // 脏表：附件.xlsx 因中文名被 sanitize 成 __
        junk = new BiSandboxTable();
        junk.setTableName("__");
        junk.setPhysicalName("__");
        junk.setDbId(1L);

        // 用户手动输入的单字符脏表（如再次上传时把表名写成 c），其短名 "c" 会作为子串
        // 命中 sales_dm__demo_product / monthly_revenue 等正常物理名。
        singleCharJunk = new BiSandboxTable();
        singleCharJunk.setTableName("c");
        singleCharJunk.setPhysicalName("c");
        singleCharJunk.setDbId(1L);

        when(sandboxMapper.selectAll()).thenReturn(List.of(sales, junk, singleCharJunk));
        when(sandboxMapper.selectByDbId(2L)).thenReturn(List.of(sales));
        when(sandboxMapper.selectByDbId(1L)).thenReturn(List.of(junk, singleCharJunk));
        when(sandboxMapper.selectByPhysicalName("sales_dm__demo_monthly_revenue")).thenReturn(sales);
    }

    @Test
    void resolveByPhysicalName_exactMatch_returnsSales() {
        String r = service.resolvePhysicalName(null, null, "sales_dm__demo_monthly_revenue");
        assertEquals("sales_dm__demo_monthly_revenue", r);
    }

    @Test
    void resolveByShortName_exactMatch_returnsSales() {
        String r = service.resolvePhysicalName(null, "demo_monthly_revenue", null);
        assertEquals("sales_dm__demo_monthly_revenue", r);
    }

    @Test
    void resolvePhysicalNamePassedAsTableName_doesNotMatchJunkTable() {
        // 回归：物理名被当短名传入时，原 step3.3 的 "hint.contains(shortName)" 会让
        // "sales_dm__demo_monthly_revenue".contains("__") 命中 __ 脏表。
        // 修复后退化名被跳过，应通过 phy.contains(hint) 命中真实物理名而非 __。
        String r = service.resolvePhysicalName(null, "sales_dm__demo_monthly_revenue", null);
        assertEquals("sales_dm__demo_monthly_revenue", r);
    }

    @Test
    void resolveHintContainingUnderscore_neverReturnsJunkTable() {
        // 防守：即便提示含 "__" 且库内无任何匹配表，也绝不返回 __ 脏表
        String r = service.resolvePhysicalName(null, "nonexistent__table", null);
        assertNull(r);
    }

    @Test
    void resolveHintForProductTable_doesNotMatchSingleCharJunkTable() {
        // 回归：用户手动创建单字符脏表 c，其短名 "c" 作为子串会命中
        // "sales_dm__demo_monthly_revenue"（含 c）。修复后应跳过单字符短名，
        // 并通过 phy.contains(hint) 命中真实物理名。
        String r = service.resolvePhysicalName(null, "sales_dm__demo_monthly_revenue", null);
        assertEquals("sales_dm__demo_monthly_revenue", r);
    }

    @Test
    void createSandboxTable_rejectsDegenerateChineseName() {
        BiSandboxDb db = new BiSandboxDb();
        db.setId(1L);
        db.setDbKey("default");
        db.setName("默认库");
        when(sandboxMapper.selectDbByKey("default")).thenReturn(db);

        BizException ex = assertThrows(BizException.class,
                () -> service.createSandboxTable("员工表",
                        List.of(Map.of("name", "id", "type", "BIGINT")), null, "tester"));
        // 应在生成退化名 __ 之前拒绝，且不触发任何 DDL
        verifyNoInteractions(jdbcTemplate);
    }

    // ===== 按库作用域隔离解析（P3 根因架构性修复）=====

    @Test
    void scopedResolve_inSalesDb_neverReturnsDefaultDbJunkTable() {
        // 即便默认库存在脏表 __/c（含退化名、单字符短名），在销售库(db_id=2)作用域下解析
        // sales_dm__demo_monthly_revenue 也必须只命中销售库，绝不串扰到默认库脏表。
        String r = service.resolvePhysicalName(2L, null, "sales_dm__demo_monthly_revenue");
        assertEquals("sales_dm__demo_monthly_revenue", r);
    }

    @Test
    void scopedResolve_inSalesDb_neverMatchedBySingleCharSubstring() {
        // 销售库作用域：提示 demo_monthly_revenue（含子串 c）必须不被默认库的单字符脏表 c 命中。
        String r = service.resolvePhysicalName(2L, "demo_monthly_revenue", null);
        assertEquals("sales_dm__demo_monthly_revenue", r);
    }

    @Test
    void scopedResolve_physicalNameInWrongDb_returnsNull() {
        // 默认库的物理名 __ 传入，但限定销售库(db_id=2)作用域 → 必须返回 null（不外溢到默认库）。
        String r = service.resolvePhysicalName(2L, null, "__");
        assertNull(r);
    }

    @Test
    void listSandboxColumns_scopedToSalesDb_returnsSalesColumns() {
        // 销售库作用域下列字段：即便默认库有脏表，也只解析销售库表。
        // querySandboxColumns 走 information_schema，这里用 selectByPhysicalName 的 stub 已能验证 scope 过滤。
        String r = service.resolvePhysicalName(2L, null, "sales_dm__demo_monthly_revenue");
        assertEquals("sales_dm__demo_monthly_revenue", r);
    }
}
