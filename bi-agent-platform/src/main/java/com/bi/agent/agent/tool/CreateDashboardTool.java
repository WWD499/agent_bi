package com.bi.agent.agent.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bi.agent.bi.domain.BiDashboard;
import com.bi.agent.bi.service.IBiDashboardService;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 工具：根据用户自然语言需求直接创建 BI 数据大屏。
 *
 * <p>属于写操作，默认需要用户确认（skipConfirm 开启时除外）。
 * 工具接收大屏名称、描述、数据源以及若干图表组件配置，
 * 调用 {@link IBiDashboardService#insertBiDashboard(BiDashboard)} 持久化，
 * 返回大屏 ID 与访问链接，供模型在最终答案中告知用户。
 */
public class CreateDashboardTool implements AgentTool {

    private final IBiDashboardService dashboardService;
    /** 用户在前端选择的数据源 ID；0 表示全部沙箱，负数表示锁定具体沙箱库 */
    private final Long defaultDatasourceId;
    private final String operator;

    public CreateDashboardTool(IBiDashboardService dashboardService, Long defaultDatasourceId, String operator) {
        this.dashboardService = dashboardService;
        this.defaultDatasourceId = defaultDatasourceId;
        this.operator = operator;
    }

    @Override
    public String name() {
        return "create_dashboard";
    }

    @Override
    public String description() {
        return "根据用户描述直接创建一个 BI 数据大屏（Dashboard）。"
                + "适用场景：用户说『帮我做一个大屏 / 生成一个数据看板 / 把这些图表放到大屏里』。"
                + "你需要先通过 run_sql / nl2sql / select_chart 拿到数据与图表配置，"
                + "再调用本工具把多个图表组件一次性保存为大屏。"
                + "每个 widget 目前推荐用 sql 模式（直接写 SELECT），chartType 可选 bar/pie/line/scatter/radar/table/stat。";
    }

    @Override
    public String jsonSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"name\":{\"type\":\"string\",\"description\":\"大屏名称（必填，如 产品销售总览）\"},"
                + "\"description\":{\"type\":\"string\",\"description\":\"大屏描述（可选）\"},"
                + "\"datasourceId\":{\"type\":\"integer\",\"description\":\"数据源ID；可选，默认使用用户当前选择的沙箱库（0=全部沙箱，负数=锁定沙箱库）\"},"
                + "\"widgets\":{\"type\":\"array\",\"description\":\"图表组件数组（至少一个）\","
                + "\"items\":{\"type\":\"object\",\"properties\":{"
                + "\"title\":{\"type\":\"string\",\"description\":\"组件标题\"},"
                + "\"chartType\":{\"type\":\"string\",\"enum\":[\"bar\",\"pie\",\"line\",\"scatter\",\"radar\",\"table\",\"stat\"],\"description\":\"图表类型\"},"
                + "\"sql\":{\"type\":\"string\",\"description\":\"手写 SQL（SELECT/WITH），表名用 sandbox.\\\"表名\\\" 全限定\"},"
                + "\"x\":{\"type\":\"integer\",\"description\":\"网格 x 坐标（0-11），可选，默认按顺序自动排列\"},"
                + "\"y\":{\"type\":\"integer\",\"description\":\"网格 y 坐标，可选\"},"
                + "\"w\":{\"type\":\"integer\",\"description\":\"宽度（2-12），可选，默认 6\"},"
                + "\"h\":{\"type\":\"integer\",\"description\":\"高度（2-20），可选，默认 10\"}"
                + "},\"required\":[\"title\",\"chartType\",\"sql\"]}}}"
                + "},\"required\":[\"name\",\"widgets\"]}";
    }

    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public String call(String argsJson) {
        String name = null;
        String description = null;
        Long datasourceId = defaultDatasourceId;
        List<JSONObject> widgets = new ArrayList<>();
        try {
            JSONObject a = JSON.parseObject(argsJson);
            name = a.getString("name");
            description = a.getString("description");
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
        if (name == null || name.trim().isEmpty()) {
            return "缺少 name 参数";
        }
        if (widgets.isEmpty()) {
            return "widgets 不能为空";
        }
        if (datasourceId == null) {
            return "datasourceId 未指定且当前会话未选择数据源";
        }
        // 安全兜底：模型若传入正数业务库 id，视为误传，回落到当前会话默认的沙箱库 id
        if (datasourceId > 0) {
            if (defaultDatasourceId != null) {
                datasourceId = defaultDatasourceId;
            } else {
                return "datasourceId 必须 ≤0（0=全部沙箱，负数=锁定沙箱库），不能是业务库正数 id：" + datasourceId;
            }
        }

        try {
            JSONArray savedWidgets = new JSONArray();
            int x = 0;
            int y = 0;
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

                // 自动换行布局：每行 12 列
                x = xi + wi;
                if (x >= 12) {
                    x = 0;
                    y = yi + hi;
                }
            }

            JSONObject config = new JSONObject();
            config.put("datasourceId", datasourceId);
            config.put("widgets", savedWidgets);

            BiDashboard dashboard = new BiDashboard();
            dashboard.setName(name.trim());
            dashboard.setDescription(description == null ? "" : description.trim());
            dashboard.setConfigJson(config.toJSONString());
            dashboard.setStatus("1");
            dashboard.setIsPublic("0");
            dashboard.setCreateBy(operator);

            int n = dashboardService.insertBiDashboard(dashboard);
            if (n <= 0) {
                return "创建大屏失败：数据库写入 0 行";
            }

            JSONObject out = new JSONObject();
            out.put("success", true);
            out.put("dashboardId", dashboard.getId());
            out.put("name", dashboard.getName());
            out.put("datasourceId", datasourceId);
            out.put("widgetCount", widgets.size());
            out.put("url", "/dashboard?id=" + dashboard.getId() + "&mode=preview");
            return out.toJSONString();
        } catch (Exception e) {
            return "创建大屏失败：" + e.getMessage();
        }
    }
}
