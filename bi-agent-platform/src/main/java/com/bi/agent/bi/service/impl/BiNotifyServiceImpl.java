package com.bi.agent.bi.service.impl;

import com.bi.agent.bi.domain.BiNotify;
import com.bi.agent.bi.mapper.BiNotifyMapper;
import com.bi.agent.bi.service.IBiNotifyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class BiNotifyServiceImpl implements IBiNotifyService {

    @Autowired
    private BiNotifyMapper notifyMapper;

    @Override
    public Long add(BiNotify notify) {
        if (notify.getIsRead() == null) {
            notify.setIsRead(0);
        }
        if (notify.getCreateTime() == null) {
            notify.setCreateTime(LocalDateTime.now());
        }
        notifyMapper.insert(notify);
        return notify.getId();
    }

    @Override
    public List<BiNotify> list(String userId, boolean unreadOnly, int page, int size) {
        int limit = size > 0 ? size : 20;
        int offset = (page > 0 ? page - 1 : 0) * limit;
        return notifyMapper.selectList(userId, unreadOnly, limit, offset);
    }

    @Override
    public BiNotify getById(Long id) {
        return notifyMapper.selectById(id);
    }

    @Override
    public int markRead(Long id, String userId) {
        return notifyMapper.updateReadById(id, userId);
    }

    @Override
    public int markAllRead(String userId) {
        return notifyMapper.updateReadAllByUser(userId);
    }

    @Override
    public long unreadCount(String userId) {
        return notifyMapper.countUnread(userId);
    }

    @Override
    public int delete(Long id, String userId) {
        return notifyMapper.deleteById(id, userId);
    }

    @Override
    public int deleteByIds(String userId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return notifyMapper.deleteByIds(userId, ids);
    }
}
