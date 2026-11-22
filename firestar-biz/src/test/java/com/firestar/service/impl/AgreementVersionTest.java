package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.firestar.entity.Agreement;
import com.firestar.entity.AgreementVersion;
import com.firestar.mapper.AgreementConsentMapper;
import com.firestar.mapper.AgreementMapper;
import com.firestar.mapper.AgreementVersionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 协议版本与 consent 留痕回归（P1 测试补齐）。
 *
 * <p>{@code AgreementServiceImpl} 全部方法此前零测试。本类锁定两条契约：
 * ① 版本发布按 v1/v2… 递增并同步 agreement.current_version_id（用户同意的锚点）；
 * ② 同意/撤回都落不可变的 consent 记录（action + 渠道 + IP + UA 全留存）。</p>
 */
@ExtendWith(MockitoExtension.class)
class AgreementVersionTest {

    @Mock private AgreementMapper agreementMapper;
    @Mock private AgreementVersionMapper versionMapper;
    @Mock private AgreementConsentMapper consentMapper;

    @InjectMocks private AgreementServiceImpl service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MybatisConfiguration cfg = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), AgreementVersion.class);
    }

    @Test
    @DisplayName("首次发布：版本 v1、状态 PUBLISHED、回写 agreement 当前版本")
    void publishVersion_first_isV1() {
        Agreement a = new Agreement();
        a.setId(9L);
        a.setCode("privacy");
        a.setName("隐私政策");
        when(versionMapper.selectOne(any())).thenReturn(null);
        when(agreementMapper.selectById(9L)).thenReturn(a);
        doAnswer(inv -> {
            AgreementVersion v = inv.getArgument(0);
            v.setId(100L);
            return 1;
        }).when(versionMapper).insert(any(AgreementVersion.class));

        Long id = service.publishVersion(9L, "第一版内容", "首版", false, 1L);

        assertEquals(100L, id);
        verify(versionMapper).insert(argThat((AgreementVersion v) ->
                "v1".equals(v.getVer())
                        && "PUBLISHED".equals(v.getStatus())
                        && Boolean.FALSE.equals(v.getNeedReconsent())
                        && "第一版内容".equals(v.getContent())));
        assertEquals(100L, a.getCurrentVersionId(), "当前版本锚点必须同步，否则用户同意指向旧版");
        verify(agreementMapper).updateById(a);
    }

    @Test
    @DisplayName("再次发布：从 v2 递增，不覆盖历史版本")
    void publishVersion_second_incrementsToV2() {
        AgreementVersion last = new AgreementVersion();
        last.setVer("v1");
        last.setId(100L);
        when(versionMapper.selectOne(any())).thenReturn(last);
        when(agreementMapper.selectById(9L)).thenReturn(null);

        service.publishVersion(9L, "第二版", "条款更新", true, 2L);

        verify(versionMapper).insert(argThat((AgreementVersion v) ->
                "v2".equals(v.getVer())
                        && Boolean.TRUE.equals(v.getNeedReconsent())
                        && "条款更新".equals(v.getChangeSummary())));
        verify(agreementMapper, never()).updateById(any(Agreement.class));
    }

    @Test
    @DisplayName("非法历史版本号（vX）不崩，回退到 v1")
    void publishVersion_malformedLastVersion_fallsBackToV1() {
        AgreementVersion last = new AgreementVersion();
        last.setVer("vX");
        when(versionMapper.selectOne(any())).thenReturn(last);
        when(agreementMapper.selectById(9L)).thenReturn(null);

        service.publishVersion(9L, "内容", "", false, 3L);

        verify(versionMapper).insert(argThat((AgreementVersion v) -> "v1".equals(v.getVer())));
    }

    @Test
    @DisplayName("同意与撤回各自落一条 consent 记录（不可变审计，不 update 旧行）")
    void consentAndWithdraw_appendOnly() {
        service.consent(7L, 9L, 100L, "MODAL", "10.0.0.1", "UA-1");
        service.withdraw(7L, 9L, 100L, "10.0.0.2", "UA-2");

        verify(consentMapper, times(2)).insert(any(com.firestar.entity.AgreementConsent.class));
        verify(consentMapper).insert(argThat((com.firestar.entity.AgreementConsent c) ->
                "AGREE".equals(c.getAction()) && "MODAL".equals(c.getChannel())
                        && "10.0.0.1".equals(c.getIp()) && "UA-1".equals(c.getUserAgent())
                        && 7L == c.getUserId() && 100L == c.getVersionId()));
        verify(consentMapper).insert(argThat((com.firestar.entity.AgreementConsent c) ->
                "WITHDRAW".equals(c.getAction()) && "SETTINGS".equals(c.getChannel())
                        && "10.0.0.2".equals(c.getIp()) && "UA-2".equals(c.getUserAgent())));
        verify(consentMapper, never()).update(any(), any());
    }
}
