package com.bi.agent.bi.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.bi.agent.bi.domain.BiAlertRecord;
import com.bi.agent.bi.domain.BiAlertRule;
import com.bi.agent.bi.domain.BiNotify;
import com.bi.agent.bi.service.IBiAlertNotifyService;
import com.bi.agent.bi.service.IBiNotifyService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * BI 预警通知服务实现（Phase 1 → 补齐邮件通道）
 *
 * <p>站内信通道：agent-bi 暂无若依 sys_notice 基础设施，Phase 3 前端接入后补；
 * 当前以结构化日志落地，便于联调与监控采集。
 * <p>邮件通道：基于 spring-boot-starter-mail，按 rule.notifyType 是否含 email 触发，
 * 收件人取规则 notifyTarget（支持多邮箱逗号/分号分隔），为空时退化为 bi.alert.mail.default-recipient。
 * <p>JavaMailSender 由 spring-boot-starter-mail 自动配置；未配置 spring.mail.host 时该 bean 不创建，
 * 邮件自动降级跳过，绝不影响预警主流程与日志通道。
 *
 * @author ruoyi-bi (ported to agent-bi)
 */
@Service
public class BiAlertNotifyServiceImpl implements IBiAlertNotifyService {

    private static final Logger log = LoggerFactory.getLogger(BiAlertNotifyServiceImpl.class);

    /** 邮件发件人显示名 */
    private static final String MAIL_PERSONAL = "AI智能BI数据分析平台";
    /** 邮箱格式校验（轻量基本形态校验） */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /**
     * JavaMailSender 由 spring-boot-starter-mail 自动配置。
     * required=false：未引入 starter 或未配 spring.mail.host 时本服务仍可正常启动（邮件通道自动降级）。
     */
    @Autowired(required = false)
    private JavaMailSender mailSender;

    /** 发件邮箱（取自 spring.mail.username） */
    @Value("${spring.mail.username:}")
    private String mailFrom;

    /** 兜底收件人（规则 notifyTarget 为空时使用） */
    @Value("${bi.alert.mail.default-recipient:}")
    private String defaultRecipient;

    /**
     * 站内信服务：预警触发后写入用户通知表（bi_notify），替代原纯日志方案。
     * required=false：即使未启用通知服务也不影响预警主流程。
     */
    @Autowired(required = false)
    private IBiNotifyService biNotifyService;

    /** 站内信默认接收人（预警由调度触发、无登录会话时回退），默认 admin（与本平台登录用户名一致） */
    @Value("${bi.notify.default-user:admin}")
    private String defaultNotifyUser;

    @Override
    public void notify(BiAlertRule rule, BiAlertRecord record) {
        // 站内信：写入用户通知表（bi_notify），前端通知中心据此展示
        if (biNotifyService != null) {
            try {
                BiNotify notify = new BiNotify();
                notify.setUserId(resolveReceiver(rule));
                notify.setRuleId(record.getRuleId());
                notify.setRecordId(record.getId());
                notify.setTitle("数据预警：" + (record.getRuleName() != null ? record.getRuleName() : "未知规则"));
                notify.setContent(buildNotifyContent(rule, record));
                notify.setLevel(record.getAlertLevel());
                notify.setIsRead(0);
                biNotifyService.add(notify);
            } catch (Exception e) {
                // 站内信失败不影响主流程；打印完整堆栈便于排查
                log.error("写入站内信通知失败（已忽略）", e);
            }
        }

        // 结构化日志保留（便于联调 / 监控采集 / 未接入前端前的兜底可见性）
        log.warn("[BI数据预警] 规则={} | 表={} | 级别={} | 消息={} | 实际值={} | 阈值={} {} | 处理人={}",
                record.getRuleName(),
                record.getTableName(),
                record.getAlertLevel(),
                record.getAlertMessage(),
                record.getActualValue(),
                record.getThresholdValue(),
                record.getComparisonOperator(),
                rule != null && StringUtils.isNotBlank(rule.getNotifyTarget())
                        ? " | 通知目标=" + rule.getNotifyTarget() : "");

        // 邮件通道
        sendEmail(rule, record);

        if (StringUtils.isNotBlank(record.getAnalysisResult())) {
            log.info("[BI数据预警-AI分析] 规则={} | 分析：{}",
                    record.getRuleName(), record.getAnalysisResult());
        }
    }

    /**
     * 解析站内信接收人：
     * 若在用户请求上下文（有登录会话）则取当前登录用户；否则回退到配置的默认用户（bi.notify.default-user）。
     * 预警当前由调度触发、无会话，通常走默认用户；多用户场景可在此扩展为「规则 → 用户」映射。
     */
    private String resolveReceiver(BiAlertRule rule) {
        try {
            if (StpUtil.isLogin()) {
                return String.valueOf(StpUtil.getLoginId());
            }
        } catch (Exception ignored) {
            // 无会话（调度触发）时忽略，走默认用户
        }
        return defaultNotifyUser;
    }

    /**
     * 组装站内信正文（保留换行，前端以 white-space: pre-wrap 渲染）。
     */
    private String buildNotifyContent(BiAlertRule rule, BiAlertRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append("监控表：").append(record.getTableName()).append("\n");
        sb.append("触发消息：").append(record.getAlertMessage()).append("\n");
        sb.append("实际值：").append(record.getActualValue() == null ? "-" : record.getActualValue())
                .append("，阈值：").append(record.getThresholdValue() == null ? "-" : record.getThresholdValue())
                .append(" ").append(record.getComparisonOperator()).append("\n");
        if (StringUtils.isNotBlank(record.getAnalysisResult())) {
            sb.append("AI 分析：").append(record.getAnalysisResult());
        }
        return sb.toString();
    }

    /**
     * 邮件通知（可选通道）
     *
     * <p>触发条件：rule.notifyType 含 "email"，且能解析出有效收件邮箱
     * （优先取 rule.notifyTarget，为空时取 bi.alert.mail.default-recipient 兜底）。
     * 任何缺失/异常都会安全降级，绝不影响主流程。
     */
    private void sendEmail(BiAlertRule rule, BiAlertRecord record) {
        if (mailSender == null) {
            log.info("邮件通知通道未启用（JavaMailSender 未配置 spring.mail.host），已跳过");
            return;
        }
        if (rule == null || StringUtils.isBlank(rule.getNotifyType())
                || !rule.getNotifyType().toLowerCase().contains("email")) {
            log.info("规则未配置邮件通知方式（notifyType={}），跳过邮件",
                    rule != null ? rule.getNotifyType() : "null");
            return;
        }

        List<String> recipients = parseEmails(rule.getNotifyTarget());
        if (recipients.isEmpty() && StringUtils.isNotBlank(defaultRecipient)) {
            recipients = parseEmails(defaultRecipient);
        }
        if (recipients.isEmpty()) {
            log.warn("规则[{}]通知方式含 email，但未配置有效收件人（notifyTarget={}），跳过邮件",
                    rule != null ? rule.getName() : "?", rule != null ? rule.getNotifyTarget() : "null");
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            String subject = "【数据预警】" + (rule != null ? rule.getName() : "未知规则");
            helper.setSubject(subject);
            if (StringUtils.isNotBlank(mailFrom)) {
                helper.setFrom(new InternetAddress(mailFrom, MAIL_PERSONAL, "UTF-8"));
            }
            helper.setTo(recipients.toArray(new String[0]));
            helper.setText(buildHtmlContent(rule, record), true);
            mailSender.send(message);
            log.info("已发送邮件预警通知至 {}：{}", recipients, subject);
        } catch (Exception e) {
            // 邮件失败不影响主流程；打印完整堆栈便于排查 SMTP/授权码问题
            log.error("发送邮件预警通知失败（收件人={}）", recipients, e);
        }
    }

    /**
     * 解析收件人字符串：支持逗号、分号、空白分隔，过滤掉非法邮箱格式
     */
    private List<String> parseEmails(String raw) {
        List<String> list = new ArrayList<>();
        if (StringUtils.isBlank(raw)) {
            return list;
        }
        for (String token : raw.split("[,;\\s]+")) {
            String email = token.trim();
            if (EMAIL_PATTERN.matcher(email).matches()) {
                list.add(email);
            }
        }
        return list;
    }

    /**
     * 构建 HTML 预警邮件（内联样式，兼容主流邮件客户端）
     */
    private String buildHtmlContent(BiAlertRule rule, BiAlertRecord record) {
        String ruleName = escapeHtml(record.getRuleName());
        String tableName = escapeHtml(record.getTableName());
        String alertMsg = escapeHtml(record.getAlertMessage());
        String actual = record.getActualValue() == null ? "-" : record.getActualValue().toString();
        String threshold = record.getThresholdValue() == null ? "-" : record.getThresholdValue().toString();
        String operator = escapeHtml(record.getComparisonOperator());
        String analysis = escapeHtml(record.getAnalysisResult());

        StringBuilder html = new StringBuilder();
        html.append("<div style=\"margin:0;padding:24px;background:#f4f6fb;font-family:-apple-system,'Segoe UI',Roboto,'PingFang SC','Microsoft YaHei',sans-serif;\">");
        html.append("  <div style=\"max-width:560px;margin:0 auto;background:#ffffff;border-radius:14px;overflow:hidden;box-shadow:0 6px 24px rgba(31,45,61,.08);\">");
        html.append("    <div style=\"background:linear-gradient(135deg,#3b82f6,#6366f1);padding:22px 28px;\">");
        html.append("      <div style=\"color:#fff;font-size:18px;font-weight:600;letter-spacing:.5px;\">⚠ 数据预警通知</div>");
        html.append("      <div style=\"color:rgba(255,255,255,.85);font-size:13px;margin-top:4px;\">AI智能BI数据分析平台</div>");
        html.append("    </div>");
        html.append("    <div style=\"padding:24px 28px;color:#1f2d3d;\">");
        html.append("      <h2 style=\"margin:0 0 16px;font-size:16px;color:#1f2d3d;\">").append(ruleName).append("</h2>");
        html.append("      <table style=\"width:100%;border-collapse:collapse;font-size:14px;\">");
        html.append(row("监控表", tableName));
        html.append(row("触发消息", alertMsg));
        html.append(row("实际值", actual));
        html.append(row("阈值", threshold + " " + operator));
        html.append("      </table>");
        if (StringUtils.isNotBlank(analysis)) {
            html.append("      <div style=\"margin-top:18px;padding:14px 16px;background:#f8fafc;border-left:3px solid #6366f1;border-radius:6px;\">");
            html.append("        <div style=\"font-size:13px;font-weight:600;color:#6366f1;margin-bottom:6px;\">AI 分析</div>");
            html.append("        <div style=\"font-size:13px;line-height:1.7;color:#475569;white-space:pre-wrap;\">").append(analysis).append("</div>");
            html.append("      </div>");
        }
        html.append("      <div style=\"margin-top:20px;font-size:12px;color:#94a3b8;\">请前往「BI数据分析 → 数据预警 → 预警记录」处理</div>");
        html.append("    </div>");
        html.append("  </div>");
        html.append("</div>");
        return html.toString();
    }

    private String row(String label, String value) {
        return "        <tr><td style=\"padding:8px 0;color:#94a3b8;width:90px;vertical-align:top;\">"
                + label + "</td><td style=\"padding:8px 0;color:#1f2d3d;font-weight:500;\">"
                + (value == null ? "-" : value) + "</td></tr>";
    }

    /**
     * HTML 特殊字符转义，防止预警内容破坏邮件结构
     */
    private String escapeHtml(String text) {
        if (StringUtils.isBlank(text)) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
