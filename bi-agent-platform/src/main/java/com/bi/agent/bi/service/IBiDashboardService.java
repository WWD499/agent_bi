package com.bi.agent.bi.service;

import com.bi.agent.bi.domain.BiDashboard;

import java.util.List;

/**
 * BI 大屏配置 Service 接口
 */
public interface IBiDashboardService {

    /** 查询大屏详情（含 configJson / thumbnail 大字段） */
    BiDashboard selectBiDashboardById(Long id);

    /** 查询大屏列表（不含大字段） */
    List<BiDashboard> selectBiDashboardList(BiDashboard dashboard);

    /** 新增大屏 */
    int insertBiDashboard(BiDashboard dashboard);

    /** 修改大屏 */
    int updateBiDashboard(BiDashboard dashboard);

    /** 批量删除大屏 */
    int deleteBiDashboardByIds(Long[] ids);

    /** 删除单个大屏 */
    int deleteBiDashboardById(Long id);

    /** 复制大屏（名称加「_副本」后缀） */
    Long copyBiDashboard(Long id);

    /** 按访问令牌获取「已公开」大屏；令牌错误或未公开返回 null（供分享页免登录读取） */
    BiDashboard selectByToken(String token);
}
