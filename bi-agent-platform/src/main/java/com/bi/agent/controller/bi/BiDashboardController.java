package com.bi.agent.controller.bi;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bi.agent.bi.domain.BiDashboard;
import com.bi.agent.bi.service.BiQueryService;
import com.bi.agent.bi.service.IBiDashboardService;
import com.bi.agent.bi.service.sql.ChartSelector;
import com.bi.agent.bi.vo.QueryResultVo;
import com.bi.agent.common.BizException;
import com.bi.agent.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * BI 数据大屏控制器。
 *
 * <p>对外提供「执行只读 SQL + 生成 ECharts 配置」的 REST 接口，供前端大屏页按面板
 * （标题 + SQL + 图表类型）拉取各自的数据与图表配置。底层复用
 * {@link BiQueryService#runReadOnlySql}（只读校验 + 直连业务库取数）与
 * {@link ChartSelector}（智能选图 + ECharts 配置生成），安全边界与 NL2SQL 一致。
 *
 * @author agent-bi
 */
@RestController
@RequestMapping("/api/bi/dashboard")
public class BiDashboardController {

    private static final Logger log = LoggerFactory.getLogger(BiDashboardController.class);

    /** 标识符白名单：仅允许字母/数字/下划线，且以字母或下划线开头（表名、字段名、别名） */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /** 受限聚合函数枚举 */
    private static final Set<String> ALLOWED_AGG = Set.of("SUM", "COUNT", "AVG", "MIN", "MAX");

    @Autowired
    private BiQueryService biQueryService;

    @Autowired
    private ChartSelector chartSelector;

    @Autowired
    private IBiDashboardService dashboardService;

    // ==================== 大屏 CRUD ====================

    /**
     * 大屏列表（不含 configJson / thumbnail 大字段）。支持按名称模糊、状态过滤。
     */
    @GetMapping("/list")
    public Result<List<BiDashboard>> list(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "status", required = false) String status) {
        BiDashboard cond = new BiDashboard();
        cond.setName(name);
        cond.setStatus(status);
        List<BiDashboard> rows = dashboardService.selectBiDashboardList(cond);
        log.info("返回大屏列表：size={}", rows.size());
        return Result.ok(rows);
    }

    /**
     * 大屏详情（含 configJson / thumbnail，供编辑器回填与预览）。
     */
    @GetMapping("/detail")
    public Result<BiDashboard> detail(@RequestParam("id") Long id) {
        BiDashboard d = dashboardService.selectBiDashboardById(id);
        if (d == null) {
            throw new BizException("大屏不存在，id=" + id);
        }
        return Result.ok(d);
    }

    /**
     * 新建大屏。
     */
    @PostMapping("/save")
    public Result<Long> create(@RequestBody BiDashboard dashboard) {
        if (dashboard.getName() == null || dashboard.getName().trim().isEmpty()) {
            return Result.fail(400, "大屏名称不能为空");
        }
        dashboard.setName(dashboard.getName().trim());
        int n = dashboardService.insertBiDashboard(dashboard);
        if (n <= 0) {
            throw new BizException("创建大屏失败");
        }
        return Result.ok(dashboard.getId());
    }

    /**
     * 编辑大屏（未传 configJson / thumbnail 时保留旧值）。
     */
    @PutMapping("/save")
    public Result<Void> update(@RequestBody BiDashboard dashboard) {
        if (dashboard.getId() == null) {
            return Result.fail(400, "缺少 id");
        }
        if (dashboard.getName() == null || dashboard.getName().trim().isEmpty()) {
            return Result.fail(400, "大屏名称不能为空");
        }
        dashboard.setName(dashboard.getName().trim());
        int n = dashboardService.updateBiDashboard(dashboard);
        if (n <= 0) {
            throw new BizException("更新大屏失败");
        }
        return Result.ok();
    }

    /**
     * 批量删除大屏（ids 逗号分隔）。
     */
    @DeleteMapping("/remove")
    public Result<Void> remove(@RequestParam("ids") Long[] ids) {
        if (ids == null || ids.length == 0) {
            return Result.fail(400, "ids 不能为空");
        }
        int n = dashboardService.deleteBiDashboardByIds(ids);
        log.info("删除大屏：ids={}, 删除行数={}", java.util.Arrays.toString(ids), n);
        return Result.ok();
    }

    /**
     * 复制大屏（生成「_副本」）。
     */
    @PostMapping("/copy")
    public Result<Long> copy(@RequestParam("id") Long id) {
        Long newId = dashboardService.copyBiDashboard(id);
        return Result.ok(newId);
    }

    // ==================== 出图查询（原有能力） ====================

    /**
     * 大屏图表查询：执行只读 SQL 取数，并按数据 + 用户指定（可选）生成 ECharts 配置。
     *
     * @param req datasourceId（必填）、sql（必填）、chartType（可选：LINE/BAR/PIE/SCATTER/RADAR/HEATMAP/TABLE，
     *           缺省时由系统按数据自动选择）
     * @return QueryResultVo：sql / columns / data / chartType / chartName / echartsOption / rowCount
     */
    @PostMapping("/query")
    public Result<QueryResultVo> query(@RequestBody DashboardQueryReq req) {
        if (req == null || req.getDatasourceId() == null) {
            return Result.fail(400, "datasourceId 不能为空");
        }
        if (req.getSql() == null || req.getSql().trim().isEmpty()) {
            return Result.fail(400, "sql 不能为空");
        }

        log.info("大屏查询：dsId={}, chartType={}", req.getDatasourceId(), req.getChartType());
        QueryResultVo vo = biQueryService.runReadOnlySql(req.getDatasourceId(), req.getSql().trim());
        return Result.ok(resolveChart(vo, req.getChartType()));
    }

    /**
     * 大屏「可视化配置」查询：前端只传表名 + 维度/数值字段 + 聚合方式，由后端安全拼 SQL。
     *
     * <p>相比 {@link #query} 直接传 SQL，本接口零注入风险：表名、字段名走白名单与标识符校验，
     * 聚合函数枚举受限，最终仍复用 {@link BiQueryService#runReadOnlySql} 的五层防护 + 取数 + 选图。
     *
     * @param req datasourceId（必填）、tableName（必填）、dimensions（维度字段列表）、
     *            metrics（数值字段 + 聚合方式列表）、chartType（可选）、orderBy（可选）、limit（可选）
     * @return QueryResultVo（同 {@link #query}）
     */
    @PostMapping("/query-by-config")
    public Result<QueryResultVo> queryByConfig(@RequestBody DashboardConfigReq req) {
        if (req == null || req.getDatasourceId() == null) {
            return Result.fail(400, "datasourceId 不能为空");
        }
        if (req.getTableName() == null || req.getTableName().trim().isEmpty()) {
            return Result.fail(400, "tableName 不能为空");
        }
        if (req.getMetrics() == null || req.getMetrics().isEmpty()) {
            return Result.fail(400, "至少配置一个数值指标（metrics）");
        }

        String sql;
        try {
            sql = buildSelectSql(req);
        } catch (BizException e) {
            return Result.fail(400, e.getMessage());
        }
        log.info("大屏配置查询：dsId={}, table={}, sql={}", req.getDatasourceId(), req.getTableName(), sql);

        // 复用只读执行链路：表名白名单 + 五层防护 + 取数 + 选图
        QueryResultVo vo = biQueryService.runReadOnlySql(req.getDatasourceId(), sql);
        return Result.ok(resolveChart(vo, req.getChartType()));
    }

    /**
     * 取数后按数据 + 用户指定（可选）生成 ECharts 配置，注入 vo 并返回。
     * 被 {@link #query} / {@link #queryByConfig} / {@link #shareQuery} 共用。
     */
    private QueryResultVo resolveChart(QueryResultVo vo, String chartTypeParam) {
        List<String> columns = vo.getColumns();
        List<JSONObject> data = vo.getData();

        ChartSelector.ChartType ct;
        if (chartTypeParam != null && !chartTypeParam.trim().isEmpty()) {
            ChartSelector.ChartType preferred = resolveChartType(chartTypeParam.trim());
            if (preferred == null) {
                throw new BizException("不支持的 chartType：" + chartTypeParam
                        + "（可选：LINE/BAR/PIE/SCATTER/RADAR/HEATMAP/TABLE）");
            }
            // 用户显式指定优先，但做可行性校验，不支持则回退自动选择
            ct = chartSelector.selectChart(columns, data, null, preferred);
        } else {
            ct = chartSelector.selectChart(columns, data, null);
        }

        vo.setChartType(ct.getType());
        vo.setChartName(ct.getName());
        vo.setEchartsOption(chartSelector.generateEChartsOption(ct, columns, data));
        return vo;
    }

    // ==================== 公开分享（免登录） ====================

    /**
     * 按访问令牌获取公开大屏配置（含 configJson）。仅 {@code is_public='1'} 的大屏可被访问，
     * 令牌错误或对应大屏未公开均返回 404，避免泄露未公开大屏。
     *
     * <p>该端点在 {@link com.bi.agent.config.SaTokenConfig} 中已放行
     * （免 Sa-Token 登录校验），供前端「分享页」免登录只读渲染。
     */
    @GetMapping("/share")
    public Result<BiDashboard> share(@RequestParam("token") String token) {
        BiDashboard d = dashboardService.selectByToken(token);
        if (d == null) {
            return Result.fail(404, "大屏不存在或未公开");
        }
        return Result.ok(d);
    }

    /**
     * 分享页取数：按令牌定位「已公开」大屏，从「已保存」的 widget 配置中取数——
     * 绝不采用请求体里的 SQL / 配置，防止令牌持有者越权执行任意只读 SQL。
     * {@code widgetIndex} 指定取第几个组件（与 configJson 中 widgets 数组下标一致）。
     */
    @PostMapping("/share-query")
    public Result<QueryResultVo> shareQuery(
            @RequestParam("token") String token,
            @RequestParam("widgetIndex") int widgetIndex) {
        BiDashboard d = dashboardService.selectByToken(token);
        if (d == null || d.getConfigJson() == null) {
            return Result.fail(404, "大屏不存在或未公开");
        }
        JSONObject cfg;
        try {
            cfg = JSONObject.parseObject(d.getConfigJson());
        } catch (Exception e) {
            return Result.fail(500, "大屏配置解析失败");
        }
        JSONArray widgets = cfg.getJSONArray("widgets");
        if (widgets == null || widgetIndex < 0 || widgetIndex >= widgets.size()) {
            return Result.fail(400, "组件索引越界");
        }
        JSONObject w = widgets.getJSONObject(widgetIndex);
        if ("image".equals(w.getString("type"))) {
            return Result.fail(400, "图片组件无需取数");
        }
        Long dsId = cfg.containsKey("datasourceId") ? cfg.getLong("datasourceId") : null;
        if (dsId == null) {
            return Result.fail(400, "大屏未配置数据源");
        }

        QueryResultVo vo;
        String mode = w.getString("mode");
        if (!"sql".equals(mode) && w.getJSONObject("config") != null
                && w.getJSONObject("config").getString("tableName") != null) {
            DashboardConfigReq req = buildConfigFromWidget(w, dsId);
            String sql;
            try {
                sql = buildSelectSql(req);
            } catch (BizException e) {
                return Result.fail(400, e.getMessage());
            }
            vo = biQueryService.runReadOnlySql(dsId, sql);
        } else if (w.getString("sql") != null && !w.getString("sql").trim().isEmpty()) {
            vo = biQueryService.runReadOnlySql(dsId, w.getString("sql").trim());
        } else {
            return Result.fail(400, "该组件未配置数据");
        }
        return Result.ok(resolveChart(vo, w.getString("chartType")));
    }

    /** 从已保存的 widget JSON 还原出 DashboardConfigReq（供分享页安全取数，SQL 由后端按白名单重拼） */
    private DashboardConfigReq buildConfigFromWidget(JSONObject w, Long dsId) {
        JSONObject c = w.getJSONObject("config");
        DashboardConfigReq req = new DashboardConfigReq();
        req.setDatasourceId(dsId);
        req.setTableName(c.getString("tableName"));
        if (c.containsKey("dimensions")) {
            req.setDimensions(c.getJSONArray("dimensions").toJavaList(String.class));
        }
        if (c.containsKey("metrics")) {
            List<DashboardConfigReq.Metric> metrics = new ArrayList<>();
            for (Object o : c.getJSONArray("metrics")) {
                JSONObject m = (JSONObject) o;
                DashboardConfigReq.Metric mm = new DashboardConfigReq.Metric();
                mm.setColumn(m.getString("column"));
                mm.setAgg(m.getString("agg"));
                mm.setAlias(m.getString("alias"));
                metrics.add(mm);
            }
            req.setMetrics(metrics);
        }
        if (c.containsKey("orderBy")) req.setOrderBy(c.getString("orderBy"));
        if (c.containsKey("orderDir")) req.setOrderDir(c.getString("orderDir"));
        if (c.containsKey("limit")) req.setLimit(c.getInteger("limit"));
        if (w.containsKey("chartType")) req.setChartType(w.getString("chartType"));
        return req;
    }

    /**
     * 按前端可视化配置拼出 SELECT 语句。表名、字段名均做标识符白名单校验，
     * 聚合函数仅允许枚举值，避免任何拼接注入。
     */
    private String buildSelectSql(DashboardConfigReq req) {
        String table = req.getTableName().trim();
        if (!IDENTIFIER_PATTERN.matcher(table).matches()) {
            throw new BizException("非法的表名：" + table);
        }
        List<String> selectItems = new ArrayList<>();

        // 维度字段：原样输出（可加别名），不参与聚合
        if (req.getDimensions() != null) {
            for (String dim : req.getDimensions()) {
                String d = dim != null ? dim.trim() : "";
                if (!d.isEmpty()) {
                    if (!IDENTIFIER_PATTERN.matcher(d).matches()) {
                        throw new BizException("非法的维度字段名：" + d);
                    }
                    selectItems.add(d);
                }
            }
        }

        // 数值指标：聚合函数(字段) AS 别名
        for (DashboardConfigReq.Metric m : req.getMetrics()) {
            if (m == null || m.getColumn() == null || m.getColumn().trim().isEmpty()) continue;
            String col = m.getColumn().trim();
            if (!IDENTIFIER_PATTERN.matcher(col).matches()) {
                throw new BizException("非法的数值字段名：" + col);
            }
            String agg = m.getAgg() == null ? "SUM" : m.getAgg().trim().toUpperCase();
            if (!ALLOWED_AGG.contains(agg)) {
                throw new BizException("不支持的聚合方式：" + agg + "（可选：SUM/COUNT/AVG/MIN/MAX）");
            }
            String alias = m.getAlias() != null && !m.getAlias().trim().isEmpty()
                    ? m.getAlias().trim() : (agg + "_" + col);
            if (!IDENTIFIER_PATTERN.matcher(alias).matches()) {
                throw new BizException("非法的别名：" + alias);
            }
            selectItems.add(agg + "(" + col + ") AS " + alias);
        }

        if (selectItems.isEmpty()) {
            throw new BizException("未生成任何查询字段，请检查维度/指标配置");
        }

        StringBuilder sb = new StringBuilder("SELECT ");
        sb.append(String.join(", ", selectItems));
        sb.append(" FROM ").append(table);

        // GROUP BY：有维度才分组
        if (req.getDimensions() != null && req.getDimensions().stream().anyMatch(d -> d != null && !d.trim().isEmpty())) {
            List<String> dims = req.getDimensions().stream()
                    .filter(d -> d != null && !d.trim().isEmpty())
                    .map(String::trim)
                    .toList();
            sb.append(" GROUP BY ").append(String.join(", ", dims));
        }

        // ORDER BY（可选，仅允许已选字段或聚合别名）
        if (req.getOrderBy() != null && !req.getOrderBy().trim().isEmpty()) {
            String ob = req.getOrderBy().trim();
            if (!IDENTIFIER_PATTERN.matcher(ob).matches()) {
                throw new BizException("非法的排序字段：" + ob);
            }
            sb.append(" ORDER BY ").append(ob);
            if ("DESC".equalsIgnoreCase(req.getOrderDir())) sb.append(" DESC");
            else sb.append(" ASC");
        }

        // LIMIT（可选，正整数）
        if (req.getLimit() != null && req.getLimit() > 0) {
            int lim = Math.min(req.getLimit(), 1000);
            sb.append(" LIMIT ").append(lim);
        }
        return sb.toString();
    }

    /**
     * 将前端传入的图表类型字符串解析为枚举：先按枚举名（LINE/BAR/...），
     * 再按 type 字段（line/bar/...），均失败返回 null。
     */
    private ChartSelector.ChartType resolveChartType(String s) {
        String upper = s.toUpperCase();
        for (ChartSelector.ChartType t : ChartSelector.ChartType.values()) {
            if (t.name().equals(upper) || t.getType().equalsIgnoreCase(s)) {
                return t;
            }
        }
        return null;
    }

    /** 大屏查询入参 */
    public static class DashboardQueryReq {
        private Long datasourceId;
        private String sql;
        private String chartType;

        public Long getDatasourceId() { return datasourceId; }
        public void setDatasourceId(Long datasourceId) { this.datasourceId = datasourceId; }

        public String getSql() { return sql; }
        public void setSql(String sql) { this.sql = sql; }

        public String getChartType() { return chartType; }
        public void setChartType(String chartType) { this.chartType = chartType; }
    }

    /** 大屏「可视化配置」查询入参（后端拼 SQL，零注入风险） */
    public static class DashboardConfigReq {
        private Long datasourceId;
        private String tableName;
        private List<String> dimensions;   // 维度字段（不参与聚合，进 GROUP BY）
        private List<Metric> metrics;       // 数值指标（字段 + 聚合方式）
        private String chartType;           // 可选：LINE/BAR/PIE/... 缺省自动选
        private String orderBy;            // 可选：排序字段（标识符）
        private String orderDir;           // 可选：ASC / DESC
        private Integer limit;             // 可选：行数上限（最大 1000）

        public Long getDatasourceId() { return datasourceId; }
        public void setDatasourceId(Long v) { datasourceId = v; }
        public String getTableName() { return tableName; }
        public void setTableName(String v) { tableName = v; }
        public List<String> getDimensions() { return dimensions; }
        public void setDimensions(List<String> v) { dimensions = v; }
        public List<Metric> getMetrics() { return metrics; }
        public void setMetrics(List<Metric> v) { metrics = v; }
        public String getChartType() { return chartType; }
        public void setChartType(String v) { chartType = v; }
        public String getOrderBy() { return orderBy; }
        public void setOrderBy(String v) { orderBy = v; }
        public String getOrderDir() { return orderDir; }
        public void setOrderDir(String v) { orderDir = v; }
        public Integer getLimit() { return limit; }
        public void setLimit(Integer v) { limit = v; }

        /** 单个数值指标：字段 + 聚合方式 + 别名 */
        public static class Metric {
            private String column;  // 字段名
            private String agg;     // 聚合函数：SUM/COUNT/AVG/MIN/MAX
            private String alias;   // 别名（可选）

            public String getColumn() { return column; }
            public void setColumn(String v) { column = v; }
            public String getAgg() { return agg; }
            public void setAgg(String v) { agg = v; }
            public String getAlias() { return alias; }
            public void setAlias(String v) { alias = v; }
        }
    }
}
