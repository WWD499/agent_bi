package com.bi.agent.agent.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bi.agent.bi.domain.BiDashboard;
import com.bi.agent.bi.service.IBiDashboardService;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 工具：在已有 BI 数据大屏中追加或替换图表组件。
 *
 * <p>属于写操作，默认需要用户确认（skipConfirm 开启时除外）。
 * 用户说“再加点图 / 在这个大屏里追加图表 / 更新大屏”时使用本工具，
 * 避免模型只能用 {@link CreateDashboardTool} 新建大屏。
 */
public class UpdateDashboardTool implements AgentTool {

    private final IBiDashboardService dashboardService;
    private final Long defaultDatasourceId;
    private final String operator;

    public UpdateDashboardTool(IBiDashboardService dashboardService, Long defaultDatasourceId, String operator) {
        this.dashboardService = dashboardService;
        this.defaultDatasourceId = defaultDatasourceId;
        this.operator = operator;
    }

    @Override
    public String name() {
        return "update_dashboard";
    }

    @Override
    public String description() {
        return "在已有的 BI 数据大屏中追加（或替换）图表组件。"
                + "适用场景：用户说『在这个大屏里再加几张图 / 把这张图也放进刚才的大屏 / 更新大屏内容』。"
                + "你需要先通过 run_sql / nl2sql / select_chart 拿到数据与图表配置，"
                + "再调用本工具追加到指定大屏。"
                + "若 append=true（默认），新 widgets 追加到现有 widgets 之后；"
                + "若 append=false，则用新 widgets 完全替换旧组件。";
    }

    @Override
    public String jsonSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"dashboardId\":{\"type\":\"integer\",\"description\":\"要更新的大屏 ID（必填）\"},"
                + "\"append\":{\"type\":\"boolean\",\"description\":\"是否追加模式；true=追加（默认），false=替换全部组件\"},"
                + "\"widgets\":{\"type\":\"array\",\"description\":\"图表组件数组（至少一个）\","
                + "\"items\":{\"type\":\"object\",\"properties\":{"
                + "\"title\":{\"type\":\"string\",\"description\":\"组件标题\"},"
                + "\"chartType\":{\"type\":\"string\",\"enum\":[\"bar\",\"pie\",\"line\",\"scatter\",\"radar\",\"table\",\"stat\"],\"description\":\"图表类型\"},"
                + "\"sql\":{\"type\":\"string\",\"description\":\"手写 SQL（SELECT/WITH），表名用 sandbox.\\\"表名\\\" 全限定\"},"
                + "\"x\":{\"type\":\"integer\",\"description\":\"网格 x 坐标（0-11），可选\"},"
                + "\"y\":{\"type\":\"integer\",\"description\":\"网格 y 坐标，可选\"},"
                + "\"w\":{\"type\":\"integer\",\"description\":\"宽度（2-12），可选，默认 6\"},"
                + "\"h\":{\"type\":\"integer\",\"description\":\"高度（2-20），可选，默认 10\"}"
                + "},\"required\":[\"title\",\"chartType\",\"sql\"]}}}"
                + "},\"required\":[\"dashboardId\",\"widgets\"]}";
    }

    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public String call(String argsJson) {
        Long dashboardId = null;
        boolean append = true;
        Long datasourceId = defaultDatasourceId;
        List<JSONObject> widgets = new ArrayList<>();
        try {
            JSONObject a = JSON.parseObject(argsJson);
            dashboardId = a.getLong("dashboardId");
            if (a.containsKey("append")) {
                append = a.getBooleanValue("append");
            }
            if (a.containsKey("datasourceId") && a.get("datasourceId") != null) {
                datasourceId = a.getLong("datasourceId");
            }
            JSONArray arr = a.getJSONArray("widgets");
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) {
                    widgets.add(arr.getJSONObject(i));
                }
            }
        } catch (Exception e) {
            return "参数解析失败：" + e.getMessage();
        }
        if (dashboardId == null) {
            return "缺少 dashboardId 参数";
        }
        if (widgets.isEmpty()) {
            return "widgets 不能为空";
        }

        BiDashboard old = dashboardService.selectBiDashboardById(dashboardId);
        if (old == null) {
            return "大屏不存在：id=" + dashboardId;
        }

        JSONObject config = new JSONObject();
        JSONArray savedWidgets = new JSONArray();
        int startX = 0;
        int startY = 0;
        if (append) {
            try {
                JSONObject oldConfig = JSON.parseObject(old.getConfigJson());
                if (oldConfig != null) {
                    if (oldConfig.containsKey("datasourceId")) {
                        datasourceId = oldConfig.getLong("datasourceId");
                    }
                    JSONArray oldWidgets = oldConfig.getJSONArray("widgets");
                    if (oldWidgets != null) {
                        for (int i = 0; i < oldWidgets.size(); i++) {
                            savedWidgets.add(oldWidgets.getJSONObject(i));
                            // 记录已有 widget 的最低占用位置，追加时从所有旧组件的下方重新开始
                            JSONObject w = oldWidgets.getJSONObject(i);
                            int wy = w.getIntValue("y");
                            int wh = w.getIntValue("h");
                            int bottom = wy + wh;
                            if (bottom > startY) startY = bottom;
                        }
                    }
                }
            } catch (Exception ignore) {
                // 旧配置解析失败则按替换处理
            }
        }

        // 安全兜底
        if (datasourceId == null) {
            return "datasourceId 未指定且当前会话未选择数据源";
        }
        if (datasourceId > 0) {
            if (defaultDatasourceId != null) {
                datasourceId = defaultDatasourceId;
            } else {
                return "datasourceId 必须 ≤0（0=全部沙箱，负数=锁定沙箱库），不能是业务库正数 id：" + datasourceId;
            }
        }

        int x = startX;
        int y = startY;
        for (int i = 0; i < widgets.size(); i++) {
            JSONObject w = widgets.get(i);
            String title = w.getString("title");
            String chartType = w.getString("chartType");
            String sql = w.getString("sql");
            if (title == null || title.trim().isEmpty() || chartType == null || sql == null || sql.trim().isEmpty()) {
                return "第 " + (i + 1) + " 个组件缺少 title / chartType / sql";
            }
            int wi = w.containsKey("w") ? w.getIntValue("w") : 6;
            int hi = w.containsKey("h") ? w.getIntValue("h") : 10;
            int xi = w.containsKey("x") ? w.getIntValue("x") : x;
            int yi = w.containsKey("y") ? w.getIntValue("y") : y;
            if (wi < 2) wi = 2;
            if (wi > 12) wi = 12;
            if (hi < 2) hi = 2;
            if (hi > 20) hi = 20;

            JSONObject saved = new JSONObject();
            saved.put("i", "w_" + System.currentTimeMillis() + "_" + i);
            saved.put("type", "chart");
            saved.put("title", title.trim());
            saved.put("chartType", chartType.trim().toLowerCase());
            saved.put("mode", "sql");
            saved.put("sql", sql.trim());
            saved.put("config", new JSONObject());
            saved.put("x", xi);
            saved.put("y", yi);
            saved.put("w", wi);
            saved.put("h", hi);
            savedWidgets.add(saved);

            x = xi + wi;
            if (x >= 12) {
                x = 0;
                y = yi + hi;
            }
        }

        config.put("datasourceId", datasourceId);
        config.put("widgets", savedWidgets);

        BiDashboard dashboard = new BiDashboard();
        dashboard.setId(dashboardId);
        dashboard.setConfigJson(config.toJSONString());
        dashboard.setUpdateBy(operator);

        try {
            int n = dashboardService.updateBiDashboard(dashboard);
            if (n <= 0) {
                return "更新大屏失败：数据库写入 0 行";
            }

            JSONObject out = new JSONObject();
            out.put("success", true);
            out.put("dashboardId", dashboardId);
            out.put("name", old.getName());
            out.put("widgetCount", savedWidgets.size());
            out.put("url", "/dashboard?id=" + dashboardId + "&mode=preview");
            return out.toJSONString();
        } catch (Exception e) {
            return "更新大屏失败：" + e.getMessage();
        }
    }
}
