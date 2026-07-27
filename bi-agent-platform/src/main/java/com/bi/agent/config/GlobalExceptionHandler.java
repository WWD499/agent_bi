package com.bi.agent.config;

import com.bi.agent.common.BizException;
import com.bi.agent.common.Result;
import cn.dev33.satoken.exception.NotLoginException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/**
 * 全局异常处理器：把异常转成统一的 Result 结构，
 * 绝不向客户端泄露堆栈（符合 fullstack-dev 安全红线）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        log.warn("BizException: code={}, msg={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    // Sa-Token 未登录：返回 401（显式优先于下方 catch-all，
    // 避免被 Exception.class 兜底成 500，导致前端/CORS 无法识别鉴权失败）。
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Result<Void>> handleNotLogin(NotLoginException e) {
        log.warn("NotLogin: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.fail(HttpStatus.UNAUTHORIZED.value(), "未登录或登录已过期"));
    }

    // 参数校验失败（@Valid）：返回 400 + 字段级错误，而非兜底 500
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ":" + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("Validation failed: {}", msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(HttpStatus.BAD_REQUEST.value(), msg));
    }

    // 上传文件超限：Tomcat 在解析阶段抛 MaxUploadSizeExceededException，
    // 返回 413 友好提示而非兜底 500（阈值见 application.yml 的 spring.servlet.multipart）
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUpload(MaxUploadSizeExceededException e) {
        log.warn("上传文件超限：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Result.fail(413, "上传文件过大，请上传小于 30MB 的图片"));
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpected(Exception e) {
        // 只记日志，向客户端返回泛化消息
        log.error("Unexpected error", e);
        return Result.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "服务内部错误");
    }
}
