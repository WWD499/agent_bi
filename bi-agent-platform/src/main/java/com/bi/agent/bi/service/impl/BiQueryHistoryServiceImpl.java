package com.bi.agent.bi.service.impl;

import com.bi.agent.bi.domain.BiQueryHistory;
import com.bi.agent.bi.mapper.BiQueryHistoryMapper;
import com.bi.agent.bi.service.IBiQueryHistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class BiQueryHistoryServiceImpl implements IBiQueryHistoryService {

    @Autowired
    private BiQueryHistoryMapper historyMapper;

    @Override
    public Long save(BiQueryHistory record) {
        if (record.getCreateTime() == null) {
            record.setCreateTime(LocalDateTime.now());
        }
        historyMapper.insert(record);
        return record.getId();
    }

    @Override
    public List<BiQueryHistory> list(String userId, Long datasourceId, String keyword, int page, int size) {
        int limit = size > 0 ? size : 20;
        int offset = (page > 0 ? page - 1 : 0) * limit;
        return historyMapper.selectList(userId, datasourceId, keyword, limit, offset);
    }

    @Override
    public BiQueryHistory getById(Long id) {
        return historyMapper.selectById(id);
    }

    @Override
    public int delete(Long id) {
        return historyMapper.deleteById(id);
    }

    @Override
    public int deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return historyMapper.deleteByIds(ids);
    }
}
