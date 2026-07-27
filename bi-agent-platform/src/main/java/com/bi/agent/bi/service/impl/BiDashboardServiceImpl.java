package com.bi.agent.bi.service.impl;

import com.bi.agent.bi.domain.BiDashboard;
import com.bi.agent.bi.mapper.BiDashboardMapper;
import com.bi.agent.bi.service.IBiDashboardService;
import com.bi.agent.common.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * BI 大屏配置 Service 实现
 */
@Service
public class BiDashboardServiceImpl implements IBiDashboardService {

    private static final Logger log = LoggerFactory.getLogger(BiDashboardServiceImpl.class);

    @Autowired
    private BiDashboardMapper dashboardMapper;

    @Override
    public BiDashboard selectBiDashboardById(Long id) {
        return dashboardMapper.selectBiDashboardById(id);
    }

    @Override
    public List<BiDashboard> selectBiDashboardList(BiDashboard dashboard) {
        return dashboardMapper.selectBiDashboardList(dashboard);
    }

    @Override
    public int insertBiDashboard(BiDashboard dashboard) {
        if (dashboard.getStatus() == null || dashboard.getStatus().isEmpty()) {
            dashboard.setStatus("1");
        }
        if (dashboard.getIsPublic() == null || dashboard.getIsPublic().isEmpty()) {
            dashboard.setIsPublic("0");
        }
        // 公开大屏自动生成访问令牌
        if ("1".equals(dashboard.getIsPublic()) && isBlank(dashboard.getAccessToken())) {
            dashboard.setAccessToken(UUID.randomUUID().toString().replace("-", ""));
        }
        log.info("新增大屏：{}", dashboard.getName());
        return dashboardMapper.insertBiDashboard(dashboard);
    }

    @Override
    public int updateBiDashboard(BiDashboard dashboard) {
        BiDashboard old = dashboardMapper.selectBiDashboardById(dashboard.getId());
        if (old == null) {
            throw new BizException("大屏不存在：id=" + dashboard.getId());
        }
        // 未传大字段时保留旧值，避免部分更新时误清空
        if (dashboard.getConfigJson() == null) {
            dashboard.setConfigJson(old.getConfigJson());
        }
        if (dashboard.getThumbnail() == null) {
            dashboard.setThumbnail(old.getThumbnail());
        }
        if (dashboard.getStatus() == null || dashboard.getStatus().isEmpty()) {
            dashboard.setStatus(old.getStatus());
        }
        if (dashboard.getIsPublic() == null || dashboard.getIsPublic().isEmpty()) {
            dashboard.setIsPublic(old.getIsPublic());
        }
        if ("1".equals(dashboard.getIsPublic()) && isBlank(dashboard.getAccessToken())) {
            String token = isBlank(old.getAccessToken())
                    ? UUID.randomUUID().toString().replace("-", "") : old.getAccessToken();
            dashboard.setAccessToken(token);
        }
        log.info("更新大屏：id={}, name={}", dashboard.getId(), dashboard.getName());
        return dashboardMapper.updateBiDashboard(dashboard);
    }

    @Override
    public int deleteBiDashboardByIds(Long[] ids) {
        if (ids == null || ids.length == 0) {
            return 0;
        }
        log.info("批量删除大屏：ids={}", (Object) ids);
        return dashboardMapper.deleteBiDashboardByIds(ids);
    }

    @Override
    public int deleteBiDashboardById(Long id) {
        return dashboardMapper.deleteBiDashboardById(id);
    }

    @Override
    public Long copyBiDashboard(Long id) {
        BiDashboard src = dashboardMapper.selectBiDashboardById(id);
        if (src == null) {
            throw new BizException("大屏不存在：id=" + id);
        }
        BiDashboard copy = new BiDashboard();
        copy.setName(src.getName() + "_副本");
        copy.setDescription(src.getDescription());
        copy.setConfigJson(src.getConfigJson());
        copy.setThumbnail(src.getThumbnail());
        copy.setStatus(src.getStatus());
        // 副本默认不公开、不复制令牌
        copy.setIsPublic("0");
        dashboardMapper.insertBiDashboard(copy);
        log.info("复制大屏：srcId={} -> newId={}", id, copy.getId());
        return copy.getId();
    }

    @Override
    public BiDashboard selectByToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return null;
        }
        // Mapper 已用 is_public='1' 过滤，令牌错误或对应大屏未公开均返回 null
        return dashboardMapper.selectByAccessToken(token.trim());
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
