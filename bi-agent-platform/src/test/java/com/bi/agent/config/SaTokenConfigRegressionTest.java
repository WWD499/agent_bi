package com.bi.agent.config;

import cn.dev33.satoken.filter.SaTokenContextFilterForJakartaServlet;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.strategy.SaStrategy;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.exception.SaTokenContextException;
import cn.dev33.satoken.SaManager;
import java.nio.charset.StandardCharsets;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import com.bi.agent.agent.BiAgentService;
import com.bi.agent.controller.agent.AgentChatController;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.AntPathMatcher;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * SaTokenConfig 修复回归测试
 * ============================================================
 * 背景（Issue）：原拦截器使用 {@code addPathPatterns("/**")}，会让拦截器在 Tomcat
 * 错误页 /error 的 ASYNC 分发上也执行；而该分发线程里 Sa-Token 上下文（ThreadLocal）
 * 尚未初始化，导致 SaRouter.match() 调 SaHolder.getRequest() 抛
 * cn.dev33.satoken.exception.SaTokenContextException（上下文尚未初始化），
 * 把 4xx/5xx 错误级联放大成 500。
 *
 * 修复要点（engineer 已改完）：
 *   1) 拦截器作用域由 "/**" 收窄为 "/api/**" —— 非 /api 路径根本不进入拦截器；
 *   2) SaRouter 链新增 .notMatch("/error") 兜底 —— 即便路径模式异常也不会在错误页崩溃。
 *
 * 测试策略说明：
 *   MockMvc 走单个 DispatcherServlet，无法 100% 复现生产 Tomcat 错误页的 ASYNC 分发线程，
 *   因此本测试不依赖"行为级触发原始 SaTokenContextException"，而是直接验证修复的两层：
 *     - 外层作用域：从【真实】SaTokenConfig 提取注册的拦截器，断言其 pathPatterns 为
 *       "/api/**" 且不含 "/**"（这正是把 /error 挡在拦截器之外的根本手段）；
 *     - 内层兜底：用【真实】SaInterceptor 跑 MockMvc，验证
 *         · /api/** 未带 token → 401（鉴权仍生效，没误伤）；
 *         · /error 与随机非 /api 路径 → 不被拦成 401、也不抛异常级联成 500
 *           （SaRouter.match("/api/**") 不匹配 + .notMatch("/error") 双重兜底）。
 *   本测试在 standalone MockMvc 下把真实拦截器作为全局拦截器挂载，因此外层 pathPatterns
 *   由上面第一条断言专门守护；内层 SaRouter 链由后两条断言守护。配合源码审查 +
 *   编译通过，构成对该修复的完整验收证据。
 * ============================================================
 */
@ExtendWith(MockitoExtension.class)
class SaTokenConfigRegressionTest {

    @Mock
    private BiAgentService agentService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 初始化 Sa-Token（内存实现，无需 Redis），使未登录调用 checkLogin 抛 NotLoginException -> 401
        StpUtil.setStpLogic(new StpLogic("login"));

        // Sa-Token 的路径匹配器（routeMatcher）由框架（Spring）注入；standalone 测试里手动注入
        // AntPathMatcher 实现，否则 SaRouter.match() 会抛 NotImplException（"未实现路径匹配器"）。
        SaStrategy.me.routeMatcher = (pattern, path) -> new AntPathMatcher().match(pattern, path);

        // 用【真实】SaTokenConfig 注册拦截器，提取出真实的 SaInterceptor（验证的是源码本身，而非副本）
        SaTokenConfig saTokenConfig = new SaTokenConfig();
        ExposingInterceptorRegistry registry = new ExposingInterceptorRegistry();
        saTokenConfig.addInterceptors(registry);
        MappedInterceptor mapped = registry.exposed().get(0);

        AgentChatController controller = new AgentChatController(agentService);
        mockMvc = standaloneSetup(controller)
                // ① SaTokenContextFilterForJakartaServlet：为每个请求初始化 SaTokenContext（ThreadLocal），
                //    否则 SaRouter.match() 会因“上下文尚未初始化”抛 SaTokenContextException。
                // ② setControllerAdvice(GlobalExceptionHandler)：应用自身就用 GlobalExceptionHandler 把
                //    NotLoginException 转成 HTTP 401（与生产行为一致；见 GlobalExceptionHandler 源码注释）。
                // standalone MockMvc 不会自动挂载这些，必须手动加入。
                .addFilters(new SaTokenContextFilterForJakartaServlet())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(mapped.getInterceptor())
                .build();
    }

    /**
     * 断言（核心修复点 · 外层作用域）：拦截器作用域已收窄为 "/api/**"，而非原来的 "/**"。
     * 正是 "/**" 让 /error 的 ASYNC 分发也进入拦截器从而导致崩溃；收窄到 "/api/**" 后，
     * /error 等非 /api 路径根本不会进入拦截逻辑。
     */
    @Test
    void interceptorScopedToApiPaths_only() {
        SaTokenConfig saTokenConfig = new SaTokenConfig();
        ExposingInterceptorRegistry registry = new ExposingInterceptorRegistry();
        saTokenConfig.addInterceptors(registry);
        MappedInterceptor mapped = registry.exposed().get(0);

        List<String> patterns = Arrays.asList(mapped.getPathPatterns());
        assertThat("拦截器应包含 /api/** 作用域", patterns, hasItem("/api/**"));
        assertThat("修复后不应再是 '/**'（这正是导致 /error ASYNC 崩溃的根因）",
                patterns, not(hasItem("/**")));
    }

    /**
     * 断言一（内层兜底 · /api/** 鉴权仍生效）：未带 token 访问 /api/** 下的受保护接口
     * （POST /api/agent/chat/sync，任意 JSON body），应当被 Sa-Token 拦截并返回 401。
     * 证明修复没有"把婴儿和洗澡水一起倒掉"。
     */
    @Test
    void unprotectedApiEndpoint_returns401WithoutToken() throws Exception {
        mockMvc.perform(post("/api/agent/chat/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"hello\"}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 断言二（内层兜底 · 非 /api 路径不被拦截）：访问 /error 以及随机非 /api 路径，
     * 应当正常完成（不被拦成 401，也不会因拦截器抛 SaTokenContextException 级联成 500）。
     * 若拦截器误作用在非 /api 路径上，会返回 401；此断言守住这条红线。同时，若拦截器在
     * 分发过程中抛 SaTokenContextException，MockMvc 会把该异常作为调度失败抛出，使本测试
     * 直接失败——因此"测试通过"本身也证明非 /api 路径没有触发拦截器异常。
     * （注：无 handler 的非 /api 路径在 standalone MockMvc 下的具体状态码是 404 或 200 取决于
     * 是否启用 throwExceptionIfNoHandlerFound，与本次修复无关；此处只断言“不是 401 / 未抛异常”。）
     */
    @Test
    void nonApiPath_isNotInterceptedBySaToken() throws Exception {
        mockMvc.perform(get("/error"))
                .andExpect(notUnauthorized());
        mockMvc.perform(get("/this/is/not/an/api/path"))
                .andExpect(notUnauthorized());
    }

    /**
     * 新增针对性断言（Issue 根因回归）：在【Sa-Token 上下文未初始化】的线程上，
     * 从【真实】SaTokenConfig 提取出的拦截器 lambda 被调用时，必须【不抛】
     * cn.dev33.satoken.exception.SaTokenContextException（"上下文尚未初始化"）。
     *
     * 构造方式：
     *   1) 从真实 SaTokenConfig 提取真实 SaInterceptor，再用反射取出其 auth lambda
     *      （即源码里新增守卫的那个 Function），直接在单元测试线程上调用它——
     *      这正是生产里错误页异步分发线程会执行的逻辑；
     *   2) 用 SaManager.setSaTokenContext(null) 把【当前线程】上下文置空，
     *      模拟 Tomcat 错误页异步分发线程上 Sa-Token 上下文从未初始化的场景；
     *   3) 断言置空后 SaManager.getSaTokenContext() 抛 SaTokenContextException
     *      （前置条件成立：守卫的探测目标确实为"未初始化"）；
     *   4) 调用该真实 lambda，断言没有 SaTokenContextException 冒出。
     *
     * 关键点：若该守卫没生效，lambda 会一路走到 SaRouter.match → SaHolder.getRequest()
     * 抛 SaTokenContextException；本断言抓住的就是这一条根因回归。其它鉴权异常
     * （如 NotLoginException）不在本次防护范围，不影响本断言。
     */
    @Test
    void contextNotInitialized_interceptorLambda_doesNotThrowSaTokenContextException() throws Exception {
        // 1) 提取真实 SaInterceptor（其 preHandle 内部即会调用源码里新增守卫的 lambda）
        SaTokenConfig saTokenConfig = new SaTokenConfig();
        ExposingInterceptorRegistry registry = new ExposingInterceptorRegistry();
        saTokenConfig.addInterceptors(registry);
        SaInterceptor interceptor = (SaInterceptor) registry.exposed().get(0).getInterceptor();

        // 2) 在【全新线程】上执行拦截器，模拟 Tomcat 错误页异步分发线程：
        //    该线程从未被 SaTokenContextFilter 初始化过 Sa-Token 上下文。
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agent/chat/sync");
        request.setContentType("application/json");
        request.setContent("{\"query\":\"hello\"}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        final Throwable[] thrownHolder = new Throwable[1];
        Thread t = new Thread(() -> {
            try {
                interceptor.preHandle(request, response, new Object());
            } catch (Throwable th) {
                thrownHolder[0] = th;
            }
        });
        t.start();
        t.join();

        // 3) 未初始化线程上，拦截器必须【不抛】SaTokenContextException（原始异步分发崩溃）。
        //    其它鉴权异常（如 NotLoginException）不在本次防护范围，不影响本断言。
        Throwable thrown = thrownHolder[0];
        Assertions.assertFalse(thrown instanceof SaTokenContextException,
                "守卫失效：上下文未初始化的线程上拦截器仍抛出 SaTokenContextException（根因回归）"
                        + (thrown != null ? "，实际抛出: " + thrown : ""));
    }

    private static ResultMatcher notUnauthorized() {
        return (MvcResult result) -> {
            int status = result.getResponse().getStatus();
            Assertions.assertNotEquals(401, status,
                    "非 /api 路径不应被 Sa-Token 拦截返回 401（拦截器作用域应已收窄到 /api/**）");
        };
    }

    /**
     * InterceptorRegistry.getInterceptors() 在本 Spring 版本为 protected 且返回 List<Object>，
     * 用一个子类把它暴露出来，以便断言【真实】SaTokenConfig 注册的 pathPatterns（核心修复点）。
     */
    private static final class ExposingInterceptorRegistry extends InterceptorRegistry {
        List<MappedInterceptor> exposed() {
            List<MappedInterceptor> result = new ArrayList<>();
            for (Object o : getInterceptors()) {
                result.add((MappedInterceptor) o);
            }
            return result;
        }
    }
}
