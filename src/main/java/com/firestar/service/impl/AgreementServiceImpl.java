package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.dto.AgreementVO;
import com.firestar.entity.Agreement;
import com.firestar.entity.AgreementConsent;
import com.firestar.entity.AgreementVersion;
import com.firestar.mapper.AgreementConsentMapper;
import com.firestar.mapper.AgreementMapper;
import com.firestar.mapper.AgreementVersionMapper;
import com.firestar.service.AgreementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 协议服务实现。
 *
 * 【activeVersion 取值口径】优先 agreement.current_version_id；为 NULL 时取该协议
 * 最新 PUBLISHED 版本（首版种子未回填 current_version_id 也能正确展示）。
 *
 * 【版本号字段】协议版本号存于列 ver（VARCHAR，如 v1/v2），避开 BaseEntity 的
 * 乐观锁列 version（INT）。实体上对应 getVer()/setVer()。
 */
@Slf4j
@Service
public class AgreementServiceImpl implements AgreementService {

    private final AgreementMapper agreementMapper;
    private final AgreementVersionMapper versionMapper;
    private final AgreementConsentMapper consentMapper;

    public AgreementServiceImpl(AgreementMapper agreementMapper,
                                AgreementVersionMapper versionMapper,
                                AgreementConsentMapper consentMapper) {
        this.agreementMapper = agreementMapper;
        this.versionMapper = versionMapper;
        this.consentMapper = consentMapper;
    }

    @Override
    public List<AgreementVO> listActive() {
        List<Agreement> agreements = agreementMapper.selectList(
                new LambdaQueryWrapper<Agreement>().eq(Agreement::getStatus, "ENABLED"));
        List<AgreementVO> vos = new ArrayList<>();
        for (Agreement a : agreements) {
            AgreementVersion v = activeVersion(a.getId());
            if (v == null) continue;
            vos.add(toVO(a, v, false));
        }
        return vos;
    }

    @Override
    public List<AgreementVO> listPending(Long userId) {
        List<Agreement> agreements = agreementMapper.selectList(
                new LambdaQueryWrapper<Agreement>().eq(Agreement::getStatus, "ENABLED"));
        List<AgreementVO> pending = new ArrayList<>();
        for (Agreement a : agreements) {
            AgreementVersion v = activeVersion(a.getId());
            if (v == null) continue;
            Long cnt = consentMapper.selectCount(new LambdaQueryWrapper<AgreementConsent>()
                    .eq(AgreementConsent::getUserId, userId)
                    .eq(AgreementConsent::getAgreementId, a.getId())
                    .eq(AgreementConsent::getVersionId, v.getId())
                    .eq(AgreementConsent::getAction, "AGREE"));
            if (cnt == null || cnt == 0) {
                AgreementVO vo = toVO(a, v, false);
                vo.setNeedReconsent(v.getNeedReconsent());
                pending.add(vo);
            }
        }
        return pending;
    }

    @Override
   public List<AgreementVO> listConsents(Long userId) {
        List<Agreement> agreements = agreementMapper.selectList(
                new LambdaQueryWrapper<Agreement>().eq(Agreement::getStatus, "ENABLED"));
        List<AgreementVO> result = new ArrayList<>();
        for (Agreement a : agreements) {
            AgreementVersion v = activeVersion(a.getId());
            if (v == null) continue;
            AgreementConsent c = consentMapper.selectOne(new LambdaQueryWrapper<AgreementConsent>()
                    .eq(AgreementConsent::getUserId, userId)
                    .eq(AgreementConsent::getAgreementId, a.getId())
                    .eq(AgreementConsent::getVersionId, v.getId())
                    .orderByDesc(AgreementConsent::getId)
                    .last("LIMIT 1"));
            AgreementVO vo = toVO(a, v, true);
            if (c != null) {
                vo.setLastAction(c.getAction());
                vo.setConsentedAt(c.getCreatedAt());
            }
            result.add(vo);
        }
        return result;
    }

    @Override
    public void consent(Long userId, Long agreementId, Long versionId, String channel, String ip, String userAgent) {
        AgreementConsent c = new AgreementConsent();
        c.setUserId(userId);
        c.setAgreementId(agreementId);
        c.setVersionId(versionId);
        c.setAction("AGREE");
        c.setChannel(channel);
        c.setIp(ip);
        c.setUserAgent(userAgent);
        consentMapper.insert(c);
    }

    @Override
    public void withdraw(Long userId, Long agreementId, Long versionId, String ip, String userAgent) {
        AgreementConsent c = new AgreementConsent();
        c.setUserId(userId);
        c.setAgreementId(agreementId);
        c.setVersionId(versionId);
        c.setAction("WITHDRAW");
        c.setChannel("SETTINGS");
        c.setIp(ip);
        c.setUserAgent(userAgent);
        consentMapper.insert(c);
    }

    @Override
    public Long publishVersion(Long agreementId, String content, String changeSummary,
                              Boolean needReconsent, Long publishedBy) {
        AgreementVersion last = versionMapper.selectOne(new LambdaQueryWrapper<AgreementVersion>()
                .eq(AgreementVersion::getAgreementId, agreementId)
                .orderByDesc(AgreementVersion::getId)
                .last("LIMIT 1"));
        int next = 1;
        if (last != null && last.getVer() != null && last.getVer().startsWith("v")) {
            try {
                next = Integer.parseInt(last.getVer().substring(1)) + 1;
            } catch (NumberFormatException ignore) {
                log.warn("无法解析协议版本号: {}", last.getVer());
            }
        }
        AgreementVersion v = new AgreementVersion();
        v.setAgreementId(agreementId);
        v.setVer("v" + next);
        v.setContent(content);
        v.setChangeSummary(changeSummary);
        v.setNeedReconsent(needReconsent != null && needReconsent);
        v.setPublishedBy(publishedBy);
        v.setStatus("PUBLISHED");
        versionMapper.insert(v);

        Agreement a = agreementMapper.selectById(agreementId);
        if (a != null) {
            a.setCurrentVersionId(v.getId());
            agreementMapper.updateById(a);
        }
        return v.getId();
    }

    @Override
    public List<AgreementVersion> listVersions(Long agreementId) {
        return versionMapper.selectList(new LambdaQueryWrapper<AgreementVersion>()
                .eq(AgreementVersion::getAgreementId, agreementId)
                .orderByDesc(AgreementVersion::getId));
    }

    private AgreementVersion activeVersion(Long agreementId) {
        Agreement a = agreementMapper.selectById(agreementId);
        if (a == null) return null;
        if (a.getCurrentVersionId() != null) {
            return versionMapper.selectById(a.getCurrentVersionId());
        }
        return versionMapper.selectOne(new LambdaQueryWrapper<AgreementVersion>()
                .eq(AgreementVersion::getAgreementId, agreementId)
                .eq(AgreementVersion::getStatus, "PUBLISHED")
                .orderByDesc(AgreementVersion::getId)
                .last("LIMIT 1"));
    }

    private AgreementVO toVO(Agreement a, AgreementVersion v, boolean stripContent) {
        AgreementVO vo = new AgreementVO();
        vo.setAgreementId(a.getId());
        vo.setCode(a.getCode());
        vo.setName(a.getName());
        vo.setVersion(v.getVer());
        vo.setVersionId(v.getId());
        vo.setChangeSummary(v.getChangeSummary());
        vo.setContent(stripContent ? null : v.getContent());
        vo.setNeedReconsent(v.getNeedReconsent());
        return vo;
    }
}
