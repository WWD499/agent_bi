package com.bi.agent.bi.service;

import com.bi.agent.bi.domain.BiAlertRecord;

import java.util.List;

/**
 * 预警记录 Service 接口
 *
 * @author agent-bi
 */
public interface IBiAlertRecordService {

    List<BiAlertRecord> selectBiAlertRecordList(BiAlertRecord record);

    BiAlertRecord selectBiAlertRecordById(Long id);

    int updateBiAlertRecord(BiAlertRecord record);

    int deleteBiAlertRecordByIds(Long[] ids);
}
