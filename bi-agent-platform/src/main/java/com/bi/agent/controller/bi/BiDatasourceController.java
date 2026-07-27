package com.bi.agent.controller.bi;

import com.bi.agent.bi.domain.BiDatasource;
import com.bi.agent.bi.service.IBiDatasourceService;
import com.bi.agent.bi.util.BiDataSourceFactory;
import com.bi.agent.bi.vo.BiDatasourceVO;
import com.bi.agent.bi.vo.DbColumnVo;
import com.bi.agent.bi.vo.DbTableVo;
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

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * BI 数据源配置控制器。
 *
 * <p>鉴权：命中 Sa-Token 的 {@code /api/**} 规则，必须登录态。
 *
 * <p>本控制器管理「数据库管理」页所需的全部数据源 CRUD，并对外屏蔽 password / jdbcUrl
 * 等敏感字段（详情 / 列表只返回 {@link BiDatasourceVO}）。
 *
 * @author agent-bi
 */
@RestController
@RequestMapping("/api/bi/datasource")
public class BiDatasourceController {

    private static final Logger log = LoggerFactory.getLogger(BiDatasourceController.class);

    @Autowired
    private IBiDatasourceService datasourceService;

    @Autowired
    private BiDataSourceFactory dataSourceFactory;

    /**
     * 列出全部数据源（仅返回安全字段，屏蔽 password / jdbcUrl）。
     */
    @GetMapping("/list")
    public Result<List<BiDatasourceVO>> list() {
        List<BiDatasource> all = datasourceService.selectBiDatasourceList(null);
        List<BiDatasourceVO> vos = all.stream().map(this::toVo).collect(Collectors.toList());
        log.info("返回数据源列表：size={}", vos.size());
        return Result.ok(vos);
    }

    /**
     * 数据源详情（屏蔽密码，供编辑表单回填非敏感字段）。
     */
    @GetMapping("/detail")
    public Result<BiDatasourceVO> detail(@RequestParam("id") Long id) {
        BiDatasource d = datasourceService.selectBiDatasourceById(id);
        if (d == null) {
            throw new BizException("数据源不存在，id=" + id);
        }
        return Result.ok(toVo(d));
    }

    /**
     * 新建数据源。
     */
    @PostMapping
    public Result<Long> create(@RequestBody BiDatasource ds) {
        validate(ds);
        int n = datasourceService.insertBiDatasource(ds);
        if (n <= 0) {
            throw new BizException("创建数据源失败");
        }
        return Result.ok(ds.getId());
    }

    /**
     * 编辑数据源。密码字段留空表示「不修改」，沿用原密码。
     */
    @PutMapping
    public Result<Void> update(@RequestBody BiDatasource ds) {
        if (ds.getId() == null) {
            throw new BizException("缺少 id");
        }
        BiDatasource existing = datasourceService.selectBiDatasourceById(ds.getId());
        if (existing == null) {
            throw new BizException("数据源不存在，id=" + ds.getId());
        }
        validate(ds);
        if (ds.getPassword() == null || ds.getPassword().isBlank()) {
            ds.setPassword(existing.getPassword());
        }
        int n = datasourceService.updateBiDatasource(ds);
        if (n <= 0) {
            throw new BizException("更新数据源失败");
        }
        // 配置已变更，销毁旧连接池，下次使用按新配置重建
        dataSourceFactory.invalidate(ds.getId());
        return Result.ok();
    }

    /**
     * 删除单个数据源。
     */
    @DeleteMapping
    public Result<Void> delete(@RequestParam("id") Long id) {
        int n = datasourceService.deleteBiDatasourceById(id);
        if (n <= 0) {
            throw new BizException("删除失败，id=" + id);
        }
        dataSourceFactory.invalidate(id);
        return Result.ok();
    }

    /**
     * 批量删除数据源（ids 以逗号分隔）。
     */
    @DeleteMapping("/batch")
    public Result<Void> deleteBatch(@RequestParam("ids") String ids) {
        List<Long> idList = Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .collect(Collectors.toList());
        if (idList.isEmpty()) {
            throw new BizException("未选择要删除的数据源");
        }
        datasourceService.deleteBiDatasourceByIds(idList.toArray(new Long[0]));
        idList.forEach(dataSourceFactory::invalidate);
        return Result.ok();
    }

    /**
     * 测试数据源连接。
     * <ul>
     *   <li>若 body 带 id：以库中已有配置为基准，叠加表单里填写的覆盖项（主机/端口/库名/类型/账号/密码），
     *       从而支持「编辑时还没保存也先测新配置」。</li>
     *   <li>若 body 不带 id：直接用表单里的完整配置测试（新建前预检）。</li>
     * </ul>
     */
    @PostMapping("/test")
    public Result<Map<String, Object>> test(@RequestBody BiDatasource req) {
        BiDatasource target;
        if (req.getId() != null) {
            BiDatasource existing = datasourceService.selectBiDatasourceById(req.getId());
            if (existing == null) {
                throw new BizException("数据源不存在，id=" + req.getId());
            }
            target = existing;
            if (req.getHost() != null) target.setHost(req.getHost());
            if (req.getPort() != null) target.setPort(req.getPort());
            if (req.getType() != null) target.setType(req.getType());
            if (req.getDatabaseName() != null) target.setDatabaseName(req.getDatabaseName());
            if (req.getUsername() != null) target.setUsername(req.getUsername());
            if (req.getPassword() != null && !req.getPassword().isBlank()) {
                target.setPassword(req.getPassword());
            }
        } else {
            target = req;
        }
        boolean ok = datasourceService.testConnection(target);
        if (!ok) {
            throw new BizException("连接失败，请检查主机、端口、账号、密码与数据库名");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("success", true);
        data.put("message", "连接成功");
        return Result.ok(data);
    }

    /**
     * 列出某数据源的全部表（含注释），供前端 NL2SQL 表单联动「目标表名」下拉。
     */
    @GetMapping("/tables")
    public Result<List<DbTableVo>> tables(@RequestParam("datasourceId") Long datasourceId) {
        List<DbTableVo> tables = datasourceService.listTables(datasourceId);
        log.info("返回数据源表列表：datasourceId={}, size={}", datasourceId, tables.size());
        return Result.ok(tables);
    }

    /**
     * 列出指定表的全部字段（含类型与注释），供大屏可视化配置器联动「字段」下拉。
     *
     * <p>表名在 service 层做白名单校验（非法标识符直接返回空列表），此处仅做基础非空校验。
     */
    @GetMapping("/columns")
    public Result<List<DbColumnVo>> columns(
            @RequestParam("datasourceId") Long datasourceId,
            @RequestParam("tableName") String tableName) {
        if (tableName == null || tableName.trim().isEmpty()) {
            return Result.fail(400, "tableName 不能为空");
        }
        List<DbColumnVo> cols = datasourceService.listColumns(datasourceId, tableName.trim());
        log.info("返回字段列表：datasourceId={}, table={}, size={}", datasourceId, tableName, cols.size());
        return Result.ok(cols);
    }

    // ===================== 私有辅助 =====================

    private void validate(BiDatasource ds) {
        if (ds.getName() == null || ds.getName().isBlank()) {
            throw new BizException("数据源名称不能为空");
        }
        if (ds.getType() == null || ds.getType().isBlank()) {
            throw new BizException("请选择数据库类型");
        }
        if (ds.getHost() == null || ds.getHost().isBlank()) {
            throw new BizException("主机地址不能为空");
        }
        if (ds.getPort() == null || ds.getPort() <= 0) {
            throw new BizException("端口不合法");
        }
        if (ds.getDatabaseName() == null || ds.getDatabaseName().isBlank()) {
            throw new BizException("数据库名不能为空");
        }
        if (ds.getUsername() == null || ds.getUsername().isBlank()) {
            throw new BizException("用户名不能为空");
        }
    }

    private BiDatasourceVO toVo(BiDatasource d) {
        BiDatasourceVO vo = new BiDatasourceVO();
        vo.setId(d.getId());
        vo.setName(d.getName());
        vo.setType(d.getType());
        vo.setHost(d.getHost());
        vo.setPort(d.getPort());
        vo.setDatabaseName(d.getDatabaseName());
        vo.setStatus(d.getStatus());
        vo.setCreateTime(d.getCreateTime());
        return vo;
    }
}
