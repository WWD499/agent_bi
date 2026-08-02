package com.bi.agent.bi.service;

import com.bi.agent.bi.domain.BiSandboxTable;
import com.bi.agent.bi.mapper.BiSandboxMapper;
import com.bi.agent.bi.service.sql.ChartSelector;
import com.bi.agent.bi.service.sql.SqlValidator;
import com.bi.agent.bi.vo.QueryResultVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证沙箱 SQL 执行时，中文（非 ASCII）表名能被正确重写为物理表名。
 */
@ExtendWith(MockitoExtension.class)
class SandboxQueryServiceChineseNameTest {

    @Mock
    private BiSandboxMapper sandboxMapper;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private SqlValidator sqlValidator;

    @SuppressWarnings("unused")
    @Mock
    private ChartSelector chartSelector;

    @InjectMocks
    private SandboxQueryService service;

    @BeforeEach
    void setup() {
        BiSandboxTable t = new BiSandboxTable();
        t.setTableName("产品销售统计表");
        t.setPhysicalName("chanpin_xiaoshou_tongji_biao");
        t.setDbId(1L);
        when(sandboxMapper.selectAll()).thenReturn(List.of(t));
        doNothing().when(sqlValidator).validate(anyString());
    }

    @Test
    void runSandboxReadOnlySql_rewritesQuotedChineseTableName() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                Map.of("产品名称", "A", "销售额", 100)
        ));

        QueryResultVo vo = service.runSandboxReadOnlySql(
                "SELECT 产品名称, 销售额 FROM sandbox.\"产品销售统计表\" ORDER BY 销售额 DESC");

        assertNotNull(vo);
        assertEquals(1, vo.getRowCount());
        // 核心断言：下发给 JDBC 的 SQL 必须把中文短名重写成物理名
        verify(jdbcTemplate).queryForList(eq(
                "SELECT 产品名称, 销售额 FROM sandbox.\"chanpin_xiaoshou_tongji_biao\" ORDER BY 销售额 DESC LIMIT 100"));
    }

    @Test
    void runSandboxReadOnlySql_rewritesUnquotedChineseTableName() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());

        service.runSandboxReadOnlySql("SELECT * FROM sandbox.产品销售统计表");

        verify(jdbcTemplate).queryForList(eq("SELECT * FROM sandbox.chanpin_xiaoshou_tongji_biao LIMIT 100"));
    }
}
