package com.bi.agent.bi.service.impl;

import com.bi.agent.bi.domain.BiAlertRecord;
import com.bi.agent.bi.mapper.BiAlertRecordMapper;
import com.bi.agent.bi.service.IBiAlertRecordService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 预警记录 Service 实现
 *
 * @author agent-bi
 */
@Service
public class BiAlertRecordServiceImpl implements IBiAlertRecordService {

    @Autowired
    private BiAlertRecordMapper alertRecordMapper;

    @Override
    public List<BiAlertRecord> selectBiAlertRecordList(BiAlertRecord record) {
        return alertRecordMapper.selectBiAlertRecordList(record);
    }

    @Override
    public BiAlertRecord selectBiAlertRecordById(Long id) {
        return alertRecordMapper.selectBiAlertRecordById(id);
    }

    @Override
    public int updateBiAlertRecord(BiAlertRecord record) {
        return alertRecordMapper.updateBiAlertRecord(record);
    }

    @Override
    public int deleteBiAlertRecordByIds(Long[] ids) {
        return alertRecordMapper.deleteBiAlertRecordByIds(ids);
    }
}
