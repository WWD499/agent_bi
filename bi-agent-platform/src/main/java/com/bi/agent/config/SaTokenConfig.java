package com.bi.agent.config;

import cn.dev33.satoken.exception.SaTokenContextException;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 最简鉴权配置（替代若依系统管理的登录/权限拦截）。
 *
 * 拦截规则：
 *  - 拦截器仅在匹配 /api/** 路径时执行（addPathPatterns("/api/**")），
 *    路径层面拦截 /api 之外的请求；再叠加 .notMatch("/error") 兜底，
 *    即便路径模式配置异常也不会在错误页上崩溃（双保险）。
 *  - /api/** 下所有请求必须处于登录态（StpUtil.checkLogin）
 *  - 放行登录/登出接口与 CORS 预检（OPTIONS），避免预检被 401 挡掉
 *
 * 根因与修复历史（关键）：
 *  上一轮仅靠 addPathPatterns("/api/**") + .notMatch("/error") 试图让错误页
 *  不进拦截器，但在异步（SSE）请求异常时，Tomcat 走
 *  ErrorReportValve → CoyoteAdapter.asyncDispatch → AsyncContextImpl 异步分发线程
 *  → ApplicationDispatcher.include("/error") 渲染错误页。该 include 复用同一个
 *  HttpServletRequest，Spring 的 MappedInterceptor 在 include 场景下解析出的
 *  lookup path 仍是【原始 /api 请求 URI】（如 /api/agent/chat），它匹配 /api/**，
 *  于是 preHandle 仍被调用；而此 include 跑在【异步分发线程】上，Sa-Token 的
 *  ThreadLocal 上下文从未初始化，SaRouter.match 一上来 SaHolder.getRequest()
 *  即抛 SaTokenContextException（"上下文尚未初始化"），冒泡到 GlobalExceptionHandler
 *  又因响应已是 text/event-stream 且已提交而触发 HttpMessageNotWritableException
 *  次级错误。
 *  由于路径层面无法 100% 挡住这条异步 include（取决于 include 时 lookup path 解析
 *  结果），现【把整条 SaRouter.match(...).check(...) 鉴权链用
 *  try/catch (SaTokenContextException) 包裹】：Sa-Token 1.44.0 中
 *  SaManager.getSaTokenContext() 永不抛异常（始终返回一个默认的
 *  SaTokenContextForThreadLocal），真正的崩溃点在 SaRouter.match 内部调用的
 *  SaHolder.getRequest()——一旦当前线程上下文未初始化（典型即错误页的异步分发
 *  线程），getRequest() 抛 SaTokenContextException（"上下文尚未初始化"），此时
 *  直接 catch 住、放行，跳过鉴权匹配，绝不冒泡到 GlobalExceptionHandler。该守卫
 *  从根上避免异步分发线程上下文未初始化导致的崩溃，同时不改动 /api/** 作用域——
 *  正常请求在上下文已初始化的常规工作线程上不会进 catch，登录鉴权
 *  （StpUtil.checkLogin）照常生效，行为完全不变。
 *  注：Sa-Token 1.44.0 的 SaManager 仅提供 getSaTokenContext()/setSaTokenContext()，
 *  并无 isSaTokenContextExist()（该 API 为新版本引入）；且 getSaTokenContext()
 *  不抛异常，故守卫须挂在 SaRouter.match 链上（真正的崩溃点），而非
 *  getSaTokenContext()。
 *
 * 注：Phase 1 已完成 Redis 接入 —— Sa-Token 的 TokenDao 由内存实现切到 Redis 持久化实现
 *     （1.44.0 为 {@code cn.dev33.satoken.dao.SaTokenDaoForRedisTemplate}，即旧版 RedisTokenDao 的继任类），
 *     承载登录态/会话的持久化（服务端重启不丢失、支持多实例共享）。
 *     Redis 连接配置见 {@code application.yml} 的 {@code spring.data.redis}；
 *     {@link com.bi.agent.config.RedisConfig} 提供 {@code RedisTemplate<String,Object>} Bean 复用其连接工厂，
 *     并显式声明 {@code SaTokenDao} Bean（= SaTokenDaoForRedisTemplate）设为活动 TokenDao。
 *     本类的拦截器鉴权逻辑（Phase 0 已定型的异步错误页守卫）保持不变。
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handler -> {
            // 异步错误页分发线程上，Sa-Token 上下文可能尚未初始化
            // （/error 的 include 复用原始 /api URI，故 notMatch("/error") 拦不住）。
            // Sa-Token 1.44.0 中 getSaTokenContext() 永不抛异常，真正的崩溃点
            // 是 SaRouter.match 内部调用的 SaHolder.getRequest()（抛 SaTokenContextException），
            // 故直接包裹整条 match 链：未初始化上下文抛异常时放行，避免崩溃。
            try {
                SaRouter.match("/api/**")
                        .notMatch("/api/auth/login")
                        .notMatch("/api/auth/logout")
                        .notMatchMethod("OPTIONS")
                        .notMatch("/error")
                        .check(StpUtil::checkLogin);
            } catch (SaTokenContextException e) {
                // 上下文未初始化（错误页异步分发线程）→ 直接放行，不触碰已提交的 SSE 响应
            }
        })).addPathPatterns("/api/**");
    }
}
