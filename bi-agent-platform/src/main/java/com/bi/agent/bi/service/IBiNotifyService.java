package com.bi.agent.bi.service;

import com.bi.agent.bi.domain.BiNotify;
import java.util.List;

/**
 * 站内信 / 用户通知服务。
 */
public interface IBiNotifyService {

    /** 写入一条通知，返回自增 id */
    Long add(BiNotify notify);

    /** 按用户分页查询（可选仅未读） */
    List<BiNotify> list(String userId, boolean unreadOnly, int page, int size);

    /** 单条详情 */
    BiNotify getById(Long id);

    /** 标记单条已读（校验归属，仅本人可操作） */
    int markRead(Long id, String userId);

    /** 全部已读（本人） */
    int markAllRead(String userId);

    /** 未读数量（本人） */
    long unreadCount(String userId);

    /** 删除单条（校验归属） */
    int delete(Long id, String userId);

    /** 批量删除（校验归属） */
    int deleteByIds(String userId, List<Long> ids);
}
