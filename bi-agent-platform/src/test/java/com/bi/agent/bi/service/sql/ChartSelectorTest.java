package com.bi.agent.bi.service.sql;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ChartSelector 选图意图识别回归测试。
 *
 * 背景：Agent 的 select_chart 工具此前把 userQuery 传成 null，
 * 导致"趋势"意图丢失，[区域, 销售额] 这类 ≤6 行数据被兜底判成饼图。
 * 修复后 userQuery 已透传，本测试锁死"趋势→折线 / 占比→饼图"的意图优先级。
 */
class ChartSelectorTest {

    private ChartSelector selector = new ChartSelector();

    /** 构造 [region_name, total_sales] 这种 2 列、6 行的区域聚合结果 */
    private List<String> columns() {
        List<String> c = new ArrayList<>();
        c.add("region_name");
        c.add("total_sales");
        return c;
    }

    private List<JSONObject> data() {
        List<JSONObject> rows = new ArrayList<>();
        String[] regions = {"东北", "华北", "华东", "华南", "华中", "西南"};
        double[] sales = {9606.67, 7794.40, 8198.97, 4056.18, 459.00, 2303.26};
        for (int i = 0; i < regions.length; i++) {
            JSONObject r = new JSONObject();
            r.put("region_name", regions[i]);
            r.put("total_sales", sales[i]);
            rows.add(r);
        }
        return rows;
    }

    /** 回归：用户说"趋势"，即使数据只有 [区域, 销售额] 6 行，也必须折线图，不能饼图 */
    @Test
    void trendIntent_overridesPieFallback() {
        ChartSelector.ChartType ct = selector.selectChart(
                columns(), data(), "分析上季度各区域销售额趋势，并生成图表");
        assertEquals(ChartSelector.ChartType.LINE, ct,
                "含'趋势'的查询应优先选折线图，而非被数据形状兜底成饼图");
    }

    /** 对照组：用户明确说"占比/构成"，应给饼图 */
    @Test
    void proportionIntent_selectsPie() {
        ChartSelector.ChartType ct = selector.selectChart(
                columns(), data(), "分析各区域销售额占比构成");
        assertEquals(ChartSelector.ChartType.PIE, ct,
                "含'占比/构成'的查询应选饼图");
    }

    /** 对照组：意图为 null（旧 bug 的等价态），退化成数据驱动 → 6 行少类别兜底饼图 */
    @Test
    void nullIntent_fallsBackToPieForFewCategories() {
        ChartSelector.ChartType ct = selector.selectChart(columns(), data(), null);
        assertEquals(ChartSelector.ChartType.PIE, ct,
                "无意图时 [区域, 销售额] ≤6 行仍按数据特征兜底为饼图（证明修复点在透传而非选择器本身）");
    }

    /** 用户直接说"折线图"，应识别为趋势意图并选折线 */
    @Test
    void lineKeyword_selectsLine() {
        ChartSelector.ChartType ct = selector.selectChart(
                columns(), data(), "我要折线图");
        assertEquals(ChartSelector.ChartType.LINE, ct,
                "用户明确说'折线图'应选折线图");
    }

    /** 用户直接说"柱状图"，应识别为比较意图并选柱状 */
    @Test
    void barKeyword_selectsBar() {
        ChartSelector.ChartType ct = selector.selectChart(
                columns(), data(), "用柱状图展示");
        assertEquals(ChartSelector.ChartType.BAR, ct,
                "用户明确说'柱状图'应选柱状图");
    }

    /** 用户直接说"饼图"，应识别为占比意图并选饼图 */
    @Test
    void pieKeyword_selectsPie() {
        ChartSelector.ChartType ct = selector.selectChart(
                columns(), data(), "画成饼图");
        assertEquals(ChartSelector.ChartType.PIE, ct,
                "用户明确说'饼图'应选饼图");
    }

    /** 显式指定 line 类型可覆盖数据形状兜底（即使 6 行类别数据原本会走饼图） */
    @Test
    void preferredTypeLine_overridesDataShape() {
        ChartSelector.ChartType ct = selector.selectChart(
                columns(), data(), null, ChartSelector.ChartType.LINE);
        assertEquals(ChartSelector.ChartType.LINE, ct,
                "显式指定 line 时应优先尊重，不应被数据形状兜底覆盖");
    }

    /** 显式指定不支持类型（对当前数据不合法）时，应回退到自动选择而非崩溃 */
    @Test
    void unsupportedPreferredType_fallsBackToAuto() {
        // [区域, 销售额] 第一列非数字，不满足 scatter 要求（前两列均须数值）
        ChartSelector.ChartType ct = selector.selectChart(
                columns(), data(), null, ChartSelector.ChartType.SCATTER);
        assertEquals(ChartSelector.ChartType.PIE, ct,
                "scatter 对 [区域, 销售额] 不适用，应回退到数据特征驱动的饼图兜底");
    }
}
