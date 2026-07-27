package com.bi.agent.bi.vo;

/**
 * 预警记录处理请求体
 *
 * @author agent-bi
 */
public class AlertHandleReq {

    /** 处理后状态：confirmed=已确认，resolved=已解决（写入 handled_time） */
    private String status;
    private String handledBy;
    private String handledRemark;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getHandledBy() { return handledBy; }
    public void setHandledBy(String handledBy) { this.handledBy = handledBy; }

    public String getHandledRemark() { return handledRemark; }
    public void setHandledRemark(String handledRemark) { this.handledRemark = handledRemark; }
}
