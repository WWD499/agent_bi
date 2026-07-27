package com.bi.agent.bi.service.impl;

import com.bi.agent.bi.domain.BiDashboard;
import com.bi.agent.bi.mapper.BiDashboardMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BI 大屏 Service 纯逻辑单元测试（守卫既有行为，不连库）。
 *
 * <p>覆盖：insert 自动补 status/isPublic 与公开令牌生成、update 未传大字段保留旧值、
 * copyBiDashboard 生成「_副本」且不复制令牌、selectByToken 对空/错令牌的 fail-safe。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BiDashboardServiceImplTest {

    @Mock
    private BiDashboardMapper dashboardMapper;

    @InjectMocks
    private BiDashboardServiceImpl service;

    // ===================== insert =====================

    @Test
    void insert_publicGeneratesTokenAndDefaults() {
        BiDashboard d = new BiDashboard();
        d.setName("销售总览");
        d.setIsPublic("1");
        // status / accessToken 故意留空，验证 Service 兜底

        ArgumentCaptor<BiDashboard> cap = ArgumentCaptor.forClass(BiDashboard.class);
        when(dashboardMapper.insertBiDashboard(cap.capture())).thenReturn(1);

        service.insertBiDashboard(d);
        BiDashboard saved = cap.getValue();

        assertThat(saved.getStatus()).isEqualTo("1");
        assertThat(saved.getIsPublic()).isEqualTo("1");
        assertThat(saved.getAccessToken()).isNotBlank();
    }

    @Test
    void insert_privateHasNoToken() {
        BiDashboard d = new BiDashboard();
        d.setName("内部看板");
        d.setIsPublic("0");

        ArgumentCaptor<BiDashboard> cap = ArgumentCaptor.forClass(BiDashboard.class);
        when(dashboardMapper.insertBiDashboard(cap.capture())).thenReturn(1);

        service.insertBiDashboard(d);
        BiDashboard saved = cap.getValue();

        assertThat(saved.getIsPublic()).isEqualTo("0");
        assertThat(saved.getAccessToken()).isNull();
    }

    // ===================== update =====================

    @Test
    void update_preservesOldLargeFieldsWhenOmitted() {
        BiDashboard old = new BiDashboard();
        old.setId(5L);
        old.setConfigJson("OLD_JSON");
        old.setThumbnail("OLD_THUMB");
        old.setStatus("1");
        old.setIsPublic("0");
        old.setAccessToken("tok");
        when(dashboardMapper.selectBiDashboardById(5L)).thenReturn(old);

        ArgumentCaptor<BiDashboard> cap = ArgumentCaptor.forClass(BiDashboard.class);
        when(dashboardMapper.updateBiDashboard(cap.capture())).thenReturn(1);

        BiDashboard upd = new BiDashboard();
        upd.setId(5L);
        upd.setName("新名称"); // 只传名称，其余不传
        service.updateBiDashboard(upd);

        BiDashboard saved = cap.getValue();
        assertThat(saved.getConfigJson()).isEqualTo("OLD_JSON");
        assertThat(saved.getThumbnail()).isEqualTo("OLD_THUMB");
        assertThat(saved.getStatus()).isEqualTo("1");
        assertThat(saved.getIsPublic()).isEqualTo("0");
        // 私有大屏更新时不触碰令牌：传入对象未带令牌即保持 null（公开大屏才会沿用/生成令牌）
        assertThat(saved.getAccessToken()).isNull();
    }

    @Test
    void update_publicSetsTokenWhenMissing() {
        BiDashboard old = new BiDashboard();
        old.setId(5L);
        old.setIsPublic("0");
        old.setAccessToken(null);
        when(dashboardMapper.selectBiDashboardById(5L)).thenReturn(old);

        ArgumentCaptor<BiDashboard> cap = ArgumentCaptor.forClass(BiDashboard.class);
        when(dashboardMapper.updateBiDashboard(cap.capture())).thenReturn(1);

        BiDashboard upd = new BiDashboard();
        upd.setId(5L);
        upd.setName("公开看板");
        upd.setIsPublic("1");
        service.updateBiDashboard(upd);

        BiDashboard saved = cap.getValue();
        assertThat(saved.getIsPublic()).isEqualTo("1");
        assertThat(saved.getAccessToken()).isNotBlank();
    }

    // ===================== copy =====================

    @Test
    void copy_appendsSuffixAndResetsPublicAndToken() {
        BiDashboard src = new BiDashboard();
        src.setId(7L);
        src.setName("原看板");
        src.setConfigJson("CFG");
        src.setIsPublic("1");
        src.setAccessToken("src-tok");
        when(dashboardMapper.selectBiDashboardById(7L)).thenReturn(src);

        AtomicLong seq = new AtomicLong(900);
        doAnswer(inv -> {
            BiDashboard a = inv.getArgument(0);
            a.setId(seq.incrementAndGet());
            return 1;
        }).when(dashboardMapper).insertBiDashboard(any());

        Long newId = service.copyBiDashboard(7L);
        assertThat(newId).isEqualTo(901L);

        ArgumentCaptor<BiDashboard> cap = ArgumentCaptor.forClass(BiDashboard.class);
        verify(dashboardMapper).insertBiDashboard(cap.capture());
        BiDashboard copy = cap.getValue();
        assertThat(copy.getName()).endsWith("_副本");
        assertThat(copy.getConfigJson()).isEqualTo("CFG");
        assertThat(copy.getIsPublic()).isEqualTo("0");
        assertThat(copy.getAccessToken()).isNull();
    }

    // ===================== selectByToken =====================

    @Test
    void selectByToken_blankReturnsNull() {
        assertThat(service.selectByToken(null)).isNull();
        assertThat(service.selectByToken("   ")).isNull();
        // 空白令牌不应触发任何 Mapper 查询
        verify(dashboardMapper, org.mockito.Mockito.never()).selectByAccessToken(any());
    }

    @Test
    void selectByToken_validReturnsDashboard() {
        BiDashboard pub = new BiDashboard();
        pub.setId(11L);
        pub.setIsPublic("1");
        when(dashboardMapper.selectByAccessToken("good-token")).thenReturn(pub);
        when(dashboardMapper.selectByAccessToken("bad-token")).thenReturn(null);

        assertThat(service.selectByToken("good-token")).isSameAs(pub);
        assertThat(service.selectByToken("bad-token")).isNull();
    }
}
