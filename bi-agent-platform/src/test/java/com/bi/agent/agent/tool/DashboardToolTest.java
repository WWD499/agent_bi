package com.bi.agent.agent.tool;

import com.bi.agent.bi.domain.BiDashboard;
import com.bi.agent.bi.service.IBiDashboardService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 大屏工具（{@code create_dashboard} / {@code update_dashboard}）的纯单测。
 *
 * <p>这两个工具属于写操作，{@code requiresConfirmation()} 恒为 true；本测试验证：
 * 工具名正确、确认标志正确、合法参数委派到 {@link IBiDashboardService}、
 * 以及缺参 / 非法 JSON / 大屏不存在等降级分支不会误调用持久化方法。
 */
@ExtendWith(MockitoExtension.class)
class DashboardToolTest {

    @Mock
    private IBiDashboardService dashboardService;

    private static final String ONE_WIDGET =
            "{\"title\":\"t\",\"chartType\":\"bar\",\"sql\":\"SELECT 1\"}";

    @Test
    void createDashboard_nameAndRequiresConfirmation() {
        CreateDashboardTool tool = new CreateDashboardTool(dashboardService, 0L, "tester");
        assertEquals("create_dashboard", tool.name());
        assertTrue(tool.requiresConfirmation());
    }

    @Test
    void createDashboard_validArgs_insertsDashboard() {
        when(dashboardService.insertBiDashboard(any())).thenReturn(1);
        CreateDashboardTool tool = new CreateDashboardTool(dashboardService, 0L, "tester");
        String args = "{\"name\":\"销售大屏\",\"widgets\":[" + ONE_WIDGET + "]}";

        String result = tool.call(args);

        ArgumentCaptor<BiDashboard> cap = ArgumentCaptor.forClass(BiDashboard.class);
        verify(dashboardService).insertBiDashboard(cap.capture());
        assertEquals("销售大屏", cap.getValue().getName());
        assertTrue(result.contains("success"), "合法创建应返回 success，实际：" + result);
    }

    @Test
    void createDashboard_missingName_returnsErrorWithoutCall() {
        CreateDashboardTool tool = new CreateDashboardTool(dashboardService, 0L, "tester");
        String result = tool.call("{\"widgets\":[" + ONE_WIDGET + "]}");
        assertTrue(result.contains("缺少 name"), "实际：" + result);
        verify(dashboardService, never()).insertBiDashboard(any());
    }

    @Test
    void createDashboard_emptyWidgets_returnsErrorWithoutCall() {
        CreateDashboardTool tool = new CreateDashboardTool(dashboardService, 0L, "tester");
        String result = tool.call("{\"name\":\"x\",\"widgets\":[]}");
        assertTrue(result.contains("widgets 不能为空"), "实际：" + result);
        verify(dashboardService, never()).insertBiDashboard(any());
    }

    @Test
    void createDashboard_invalidJson_returnsError() {
        CreateDashboardTool tool = new CreateDashboardTool(dashboardService, 0L, "tester");
        String result = tool.call("not-a-json");
        assertTrue(result.contains("参数解析失败"), "实际：" + result);
    }

    @Test
    void updateDashboard_nameAndRequiresConfirmation() {
        UpdateDashboardTool tool = new UpdateDashboardTool(dashboardService, 0L, "tester");
        assertEquals("update_dashboard", tool.name());
        assertTrue(tool.requiresConfirmation());
    }

    @Test
    void updateDashboard_missingDashboardId_returnsError() {
        UpdateDashboardTool tool = new UpdateDashboardTool(dashboardService, 0L, "tester");
        String result = tool.call("{\"widgets\":[" + ONE_WIDGET + "]}");
        assertTrue(result.contains("缺少 dashboardId"), "实际：" + result);
        verify(dashboardService, never()).updateBiDashboard(any());
    }

    @Test
    void updateDashboard_notFound_returnsError() {
        when(dashboardService.selectBiDashboardById(99L)).thenReturn(null);
        UpdateDashboardTool tool = new UpdateDashboardTool(dashboardService, 0L, "tester");
        String result = tool.call("{\"dashboardId\":99,\"widgets\":[" + ONE_WIDGET + "]}");
        assertTrue(result.contains("大屏不存在"), "实际：" + result);
        verify(dashboardService, never()).updateBiDashboard(any());
    }

    @Test
    void updateDashboard_validArgs_updatesDashboard() {
        BiDashboard old = new BiDashboard();
        old.setName("旧大屏");
        old.setConfigJson("{\"widgets\":[]}");
        when(dashboardService.selectBiDashboardById(1L)).thenReturn(old);
        when(dashboardService.updateBiDashboard(any())).thenReturn(1);

        UpdateDashboardTool tool = new UpdateDashboardTool(dashboardService, 0L, "tester");
        String result = tool.call("{\"dashboardId\":1,\"widgets\":[" + ONE_WIDGET + "]}");

        verify(dashboardService).updateBiDashboard(any());
        assertTrue(result.contains("success"), "合法更新应返回 success，实际：" + result);
    }
}
