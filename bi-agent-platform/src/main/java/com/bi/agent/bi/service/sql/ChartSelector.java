package com.bi.agent.bi.service.sql;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 智能选图工具
 *
 * 根据SQL查询结果的数据特征 + 用户查询意图，自动推荐最合适的图表类型
 * 支持：柱状图、折线图、饼图、散点图、雷达图、表格
 *
 * @author ruoyi-bi (ported to agent-bi)
 */
@Component
public class ChartSelector {

    private static final Logger log = LoggerFactory.getLogger(ChartSelector.class);

    /**
     * 图表类型枚举
     */
    public enum ChartType {
        BAR("bar", "柱状图", "适合比较不同类别的数据"),
        LINE("line", "折线图", "适合展示数据随时间变化的趋势"),
        PIE("pie", "饼图", "适合展示各部分占整体的比例"),
        SCATTER("scatter", "散点图", "适合展示两个变量之间的关系"),
        RADAR("radar", "雷达图", "适合多维度对比"),
        HEATMAP("heatmap", "热力图", "适合展示密度分布"),
        TABLE("table", "表格", "适合展示详细数据");

        private final String type;
        private final String name;
        private final String description;

        ChartType(String type, String name, String description) {
            this.type = type;
            this.name = name;
            this.description = description;
        }

        public String getType() { return type; }
        public String getName() { return name; }
        public String getDescription() { return description; }
    }

    // ==================== 选图入口 ====================

    /**
     * 兼容旧调用（无用户意图）
     */
    public ChartType selectChart(List<String> columns, List<JSONObject> data) {
        return selectChart(columns, data, null);
    }

    /**
     * 根据数据特征 + 用户查询意图推荐图表类型
     *
     * @param columns    列名列表
     * @param data       数据列表（每行是一个JSON对象）
     * @param userQuery  用户原始自然语言查询（可为null）
     */
    public ChartType selectChart(List<String> columns, List<JSONObject> data, String userQuery) {
        return selectChart(columns, data, userQuery, null);
    }

    /**
     * 根据数据特征 + 用户查询意图 + 显式指定类型推荐图表类型。
     * 若调用方（或用户通过模型参数）显式指定了 chartType，且数据形状基本支持，则优先尊重该指定。
     *
     * @param columns       列名列表
     * @param data          数据列表（每行是一个JSON对象）
     * @param userQuery     用户原始自然语言查询（可为null）
     * @param preferredType 调用方显式指定的图表类型（可为null）
     */
    public ChartType selectChart(List<String> columns, List<JSONObject> data, String userQuery, ChartType preferredType) {
        if (columns == null || columns.isEmpty() || data == null || data.isEmpty()) {
            return ChartType.TABLE;
        }

        int colCount = columns.size();
        int rowCount = data.size();
        log.info("智能选图：列数={}，行数={}，列名={}，用户意图={}，指定类型={}", colCount, rowCount, columns, userQuery, preferredType);

        // 若调用方显式指定了图表类型，优先尊重，但做基本可行性校验
        if (preferredType != null && isChartTypeApplicable(preferredType, columns, data)) {
            log.info("选图：{}（显式指定）", preferredType.getName());
            return preferredType;
        }

        boolean firstColTime = isTimeColumn(columns.get(0), data);
        boolean firstColNumeric = isNumericColumn(columns.get(0), data);

        // ---- 用户意图优先（最高优先级） ----

        // 意图A：占比/比例/分布 → 饼图
        // 注意：上限放宽到31，覆盖「每月(12行)/每天(31行)」等时间维度占比，
        // 否则时间序列表征会被 rowCount<=10 误杀、降级成折线图。
        if (isProportionIntent(userQuery) && !firstColNumeric && rowCount <= 31) {
            if (hasAnyNumericColumn(columns, data, 1)) {
                log.info("选图：饼图（用户意图：占比/比例/分布）");
                return ChartType.PIE;
            }
        }

        // 意图B：趋势/变化/增长 → 折线图
        if (isTrendIntent(userQuery) && colCount >= 2) {
            if (hasAnyNumericColumn(columns, data, 1)) {
                log.info("选图：折线图（用户意图：趋势/变化）");
                return ChartType.LINE;
            }
        }

        // 意图C：比较/排名/对比/Top → 柱状图
        if (isComparisonIntent(userQuery) && colCount >= 2 && !firstColNumeric) {
            if (hasAnyNumericColumn(columns, data, 1)) {
                log.info("选图：柱状图（用户意图：比较/排名）");
                return ChartType.BAR;
            }
        }

        // 意图D：关系/相关性/关联 → 散点图
        if (isCorrelationIntent(userQuery) && colCount >= 2) {
            boolean col1Num = isNumericColumn(columns.get(0), data);
            boolean col2Num = isNumericColumn(columns.get(1), data);
            if (col1Num && col2Num) {
                log.info("选图：散点图（用户意图：关系/相关性）");
                return ChartType.SCATTER;
            }
        }

        // 意图E：多维度/综合评估/全面对比 → 雷达图
        if (isRadarIntent(userQuery) && colCount >= 4) {
            if (countNumericColumns(columns, data, 1) >= 3) {
                log.info("选图：雷达图（用户意图：多维度对比）");
                return ChartType.RADAR;
            }
        }

        // 意图F：明细/详细/列表/原始数据 → 表格
        if (isTableIntent(userQuery)) {
            log.info("选图：表格（用户意图：查看明细）");
            return ChartType.TABLE;
        }

        // ---- 数据特征驱动 ----

        // 2列场景
        if (colCount == 2) {
            boolean secondColNumeric = isNumericColumn(columns.get(1), data);

            if (firstColTime && secondColNumeric) {
                log.info("选图：折线图（时间趋势）");
                return ChartType.LINE;
            }
            if (firstColNumeric && secondColNumeric) {
                log.info("选图：散点图（数值相关性）");
                return ChartType.SCATTER;
            }
            if (secondColNumeric && rowCount <= 10 && isPercentageColumn(columns.get(1), data)) {
                log.info("选图：饼图（占比分布，总和≈100）");
                return ChartType.PIE;
            }
            if (secondColNumeric && rowCount <= 6 && !firstColNumeric) {
                log.info("选图：饼图（极少量类别≤6）");
                return ChartType.PIE;
            }
            if (secondColNumeric) {
                log.info("选图：柱状图（类别比较）");
                return ChartType.BAR;
            }
        }

        // 3列场景
        if (colCount == 3) {
            boolean secondColNumeric = isNumericColumn(columns.get(1), data);
            boolean thirdColNumeric = isNumericColumn(columns.get(2), data);

            if (firstColTime && secondColNumeric && thirdColNumeric) {
                log.info("选图：折线图（多指标趋势）");
                return ChartType.LINE;
            }
            if (!firstColNumeric && secondColNumeric && thirdColNumeric) {
                log.info("选图：柱状图（分组对比）");
                return ChartType.BAR;
            }
            if (!firstColNumeric && !secondColNumeric && thirdColNumeric) {
                log.info("选图：柱状图（二维分组）");
                return ChartType.BAR;
            }
        }

        // 4+列场景
        if (colCount >= 4) {
            if (!firstColNumeric && colCount <= 6 && rowCount <= 10) {
                int numericCount = countNumericColumns(columns, data, 1);
                if (numericCount >= 3) {
                    log.info("选图：雷达图（多维度对比）");
                    return ChartType.RADAR;
                }
            }
            log.info("选图：柱状图（多列默认）");
            return ChartType.BAR;
        }

        // 1列场景
        log.info("选图：表格（单列数据）");
        return ChartType.TABLE;
    }

    /**
     * 判断显式指定的图表类型是否适用于当前数据形状（基本可行性校验，不保证美观）。
     */
    private boolean isChartTypeApplicable(ChartType type, List<String> columns, List<JSONObject> data) {
        int colCount = columns.size();
        int rowCount = data.size();
        switch (type) {
            case LINE:
            case BAR:
                return colCount >= 2 && hasAnyNumericColumn(columns, data, 1);
            case PIE:
                return colCount >= 2
                        && !isNumericColumn(columns.get(0), data)
                        && hasAnyNumericColumn(columns, data, 1)
                        && rowCount <= 31;
            case SCATTER:
                if (colCount < 2) return false;
                return isNumericColumn(columns.get(0), data) && isNumericColumn(columns.get(1), data);
            case RADAR:
                if (colCount < 4) return false;
                return !isNumericColumn(columns.get(0), data) && countNumericColumns(columns, data, 1) >= 3;
            case TABLE:
                return true;
            default:
                return false;
        }
    }

    // ==================== 意图识别 ====================

    /**
     * 判断用户查询是否表达"占比/比例/分布"意图
     */
    private boolean isProportionIntent(String userQuery) {
        if (userQuery == null) return false;
        String q = userQuery.toLowerCase();
        return q.contains("占比") || q.contains("比例") || q.contains("分布") ||
               q.contains("百分比") || q.contains("构成") || q.contains("份额") ||
               q.contains("饼图") || q.contains("pie") ||
               q.contains("proportion") || q.contains("percentage") || q.contains("ratio") ||
               q.contains("distribution") || q.contains("composition");
    }

    /**
     * 判断用户查询是否表达"趋势/变化"意图
     */
    private boolean isTrendIntent(String userQuery) {
        if (userQuery == null) return false;
        String q = userQuery.toLowerCase();
        return q.contains("趋势") || q.contains("变化") || q.contains("走势") ||
               q.contains("增长") || q.contains("下降") || q.contains("波动") ||
               q.contains("折线") || q.contains("line") ||
               q.contains("trend") || q.contains("change") || q.contains("growth");
    }

    /**
     * 判断用户查询是否表达"比较/排名/对比"意图
     */
    private boolean isComparisonIntent(String userQuery) {
        if (userQuery == null) return false;
        String q = userQuery.toLowerCase();
        return q.contains("比较") || q.contains("对比") || q.contains("排名") ||
               q.contains("排行") || q.contains("最多") || q.contains("最少") ||
               q.contains("最高") || q.contains("最低") || q.contains("最大") ||
               q.contains("最小") || q.contains("柱状") || q.contains("bar") ||
               q.contains("top") || q.contains("前") ||
               q.contains("排序") || q.contains("compare") || q.contains("rank");
    }

    /**
     * 判断用户查询是否表达"关系/相关性"意图
     */
    private boolean isCorrelationIntent(String userQuery) {
        if (userQuery == null) return false;
        String q = userQuery.toLowerCase();
        return q.contains("关系") || q.contains("相关") || q.contains("关联") ||
               q.contains("影响") || q.contains("散点") || q.contains("correlation") ||
               q.contains("scatter") || q.contains("relationship");
    }

    /**
     * 判断用户查询是否表达"多维度对比"意图
     */
    private boolean isRadarIntent(String userQuery) {
        if (userQuery == null) return false;
        String q = userQuery.toLowerCase();
        return q.contains("多维度") || q.contains("综合") || q.contains("全面") ||
               q.contains("雷达") || q.contains("评估") || q.contains("radar") ||
               q.contains("multidimensional");
    }

    /**
     * 判断用户查询是否表达"查看明细/列表"意图
     */
    private boolean isTableIntent(String userQuery) {
        if (userQuery == null) return false;
        String q = userQuery.toLowerCase();
        return q.contains("明细") || q.contains("详细") || q.contains("列表") ||
               q.contains("所有") || q.contains("全部") || q.contains("表格") ||
               q.contains("原始数据") || q.contains("detail") || q.contains("list") ||
               q.contains("all");
    }

    // ==================== 列类型判断 ====================

    private boolean hasAnyNumericColumn(List<String> columns, List<JSONObject> data, int startIdx) {
        for (int i = startIdx; i < columns.size(); i++) {
            if (isNumericColumn(columns.get(i), data)) return true;
        }
        return false;
    }

    private int countNumericColumns(List<String> columns, List<JSONObject> data, int startIdx) {
        int count = 0;
        for (int i = startIdx; i < columns.size(); i++) {
            if (isNumericColumn(columns.get(i), data)) count++;
        }
        return count;
    }

    private boolean isTimeColumn(String columnName, List<JSONObject> data) {
        String lowerName = columnName.toLowerCase();
        if (lowerName.contains("time") || lowerName.contains("date") ||
            lowerName.contains("month") || lowerName.contains("year") || lowerName.contains("day") ||
            lowerName.contains("日期") || lowerName.contains("时间") ||
            lowerName.contains("月份") || lowerName.contains("年份") || lowerName.contains("周")) {
            return true;
        }
        String sampleValue = data.get(0).getString(columnName);
        if (sampleValue == null) return false;
        // 日期格式：2024-01-15, 2024/01/15, 2024-01
        if (sampleValue.matches("\\d{4}[-/]\\d{1,2}([-/]\\d{1,2})?")) return true;
        // 年月格式：202401
        if (sampleValue.matches("\\d{6}") && Integer.parseInt(sampleValue.substring(4, 6)) <= 12) return true;
        // 纯年份：2024
        if (sampleValue.matches("\\d{4}") && Integer.parseInt(sampleValue) >= 2000 && Integer.parseInt(sampleValue) <= 2099) return true;
        return false;
    }

    private boolean isNumericColumn(String columnName, List<JSONObject> data) {
        String sampleValue = data.get(0).getString(columnName);
        if (sampleValue == null) return false;
        try {
            Double.parseDouble(sampleValue);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isPercentageColumn(String columnName, List<JSONObject> data) {
        if (!isNumericColumn(columnName, data)) return false;
        double sum = 0;
        for (JSONObject row : data) {
            String value = row.getString(columnName);
            if (value != null) {
                try { sum += Double.parseDouble(value); } catch (NumberFormatException e) { return false; }
            }
        }
        return sum >= 90 && sum <= 110;
    }

    // ==================== ECharts 配置生成 ====================

    public JSONObject generateEChartsOption(ChartType chartType, List<String> columns, List<JSONObject> data) {
        JSONObject option = new JSONObject();

        option.put("tooltip", new JSONObject().fluentPut("trigger", "axis"));
        option.put("legend", new JSONObject().fluentPut("data", new JSONArray()));

        // X轴数据（第一列）
        JSONArray xData = new JSONArray();
        for (JSONObject row : data) {
            xData.add(row.getString(columns.get(0)));
        }

        // Y轴数据（第二列及以后）
        JSONArray series = new JSONArray();
        for (int i = 1; i < columns.size(); i++) {
            String column = columns.get(i);
            JSONObject serie = new JSONObject();
            serie.put("name", column);
            serie.put("type", chartType.getType());
            JSONArray values = new JSONArray();
            for (JSONObject row : data) {
                String value = row.getString(column);
                if (value != null) {
                    try { values.add(Double.parseDouble(value)); } catch (NumberFormatException e) { values.add(0); }
                } else { values.add(0); }
            }
            serie.put("data", values);
            series.add(serie);
        }

        switch (chartType) {
            case BAR:
            case LINE:
                option.put("xAxis", new JSONObject().fluentPut("type", "category").fluentPut("data", xData));
                option.put("yAxis", new JSONObject().fluentPut("type", "value"));
                option.put("series", series);
                break;

            case PIE:
                JSONArray pieData = new JSONArray();
                JSONArray pieValues = series.getJSONObject(0).getJSONArray("data");
                for (int i = 0; i < xData.size(); i++) {
                    JSONObject item = new JSONObject();
                    item.put("name", xData.getString(i));
                    item.put("value", pieValues.getDouble(i));
                    pieData.add(item);
                }
                JSONObject pieSerie = new JSONObject();
                pieSerie.put("type", "pie");
                pieSerie.put("radius", "60%");
                pieSerie.put("data", pieData);
                option.remove("tooltip");
                option.put("tooltip", new JSONObject().fluentPut("trigger", "item").fluentPut("formatter", "{b}: {c} ({d}%)"));
                option.put("series", new JSONArray().fluentAdd(pieSerie));
                break;

            case SCATTER:
                JSONArray scatterData = new JSONArray();
                for (JSONObject row : data) {
                    JSONArray point = new JSONArray();
                    try { point.add(Double.parseDouble(row.getString(columns.get(0)))); } catch (Exception e) { point.add(0); }
                    try { point.add(Double.parseDouble(row.getString(columns.get(1)))); } catch (Exception e) { point.add(0); }
                    scatterData.add(point);
                }
                JSONObject scatterSerie = new JSONObject();
                scatterSerie.put("type", "scatter");
                scatterSerie.put("data", scatterData);
                scatterSerie.put("symbolSize", 10);
                option.remove("tooltip");
                option.put("tooltip", new JSONObject().fluentPut("trigger", "item").fluentPut("formatter", "{c}"));
                option.put("xAxis", new JSONObject().fluentPut("type", "value").fluentPut("name", columns.get(0)).fluentPut("scale", true));
                option.put("yAxis", new JSONObject().fluentPut("type", "value").fluentPut("name", columns.get(1)).fluentPut("scale", true));
                option.put("series", new JSONArray().fluentAdd(scatterSerie));
                break;

            case RADAR:
                JSONArray radarIndicators = new JSONArray();
                for (int i = 1; i < columns.size(); i++) {
                    radarIndicators.add(new JSONObject().fluentPut("name", columns.get(i)).fluentPut("max", 100));
                }
                JSONArray radarSeriesData = new JSONArray();
                for (JSONObject row : data) {
                    JSONArray radarValues = new JSONArray();
                    for (int i = 1; i < columns.size(); i++) {
                        try { radarValues.add(Double.parseDouble(row.getString(columns.get(i)))); } catch (Exception e) { radarValues.add(0); }
                    }
                    radarSeriesData.add(new JSONObject().fluentPut("name", row.getString(columns.get(0))).fluentPut("value", radarValues));
                }
                JSONObject radarSerie = new JSONObject();
                radarSerie.put("type", "radar");
                radarSerie.put("data", radarSeriesData);
                option.remove("tooltip");
                option.put("tooltip", new JSONObject().fluentPut("trigger", "item"));
                option.put("radar", new JSONObject().fluentPut("indicator", radarIndicators));
                option.put("series", new JSONArray().fluentAdd(radarSerie));
                break;

            case TABLE:
                // ECharts 不支持 "table" 系列；前端会根据 chartType 跳过 ChartBlock，
                // 仅用 el-table 展示明细数据。此处返回空配置避免前端误渲染。
                option.put("series", new JSONArray());
                break;

            default:
                option.put("xAxis", new JSONObject().fluentPut("type", "category").fluentPut("data", xData));
                option.put("yAxis", new JSONObject().fluentPut("type", "value"));
                option.put("series", series);
        }

        return option;
    }
}
