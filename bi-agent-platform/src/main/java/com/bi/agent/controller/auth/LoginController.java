package com.bi.agent.controller.auth;

import cn.dev33.satoken.stp.StpUtil;
import com.bi.agent.common.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class LoginController {

    // Phase 0 stub：仅演示 Sa-Token 登录拦截，不做真实账号校验。
    // Phase 1 可接动态数据源里的用户表做真实认证。
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginReq req) {
        // 以 username 作为登录主体（Phase 1 替换为真实用户 id）
        StpUtil.login(req.username());
        return Result.ok(new LoginVO(StpUtil.getTokenValue(), req.username()));
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        StpUtil.logout();
        return Result.ok();
    }

    public record LoginVO(String token, String username) {
    }
}
