package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.firestar.dto.AboutVO;
import com.firestar.dto.ConfigItemVO;
import com.firestar.entity.Config;
import com.firestar.mapper.ConfigMapper;
import com.firestar.service.ConfigService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ConfigServiceImpl implements ConfigService {

    /** 关于我们弹窗需要的配置 key（与 V25 种子对齐） */
    private static final List<String> ABOUT_KEYS = Arrays.asList(
            "system_name", "icp_no", "support_contact", "about_us", "app_version", "logo_url");

    private final ConfigMapper configMapper;

    public ConfigServiceImpl(ConfigMapper configMapper) {
        this.configMapper = configMapper;
    }

    @Override
    public Map<String, List<ConfigItemVO>> getGrouped() {
        List<Config> all = configMapper.selectList(
                new LambdaQueryWrapper<Config>().orderByAsc(Config::getConfigType));
        Map<String, List<ConfigItemVO>> map = new LinkedHashMap<>();
        for (Config c : all) {
            ConfigItemVO vo = new ConfigItemVO();
            vo.setConfigKey(c.getConfigKey());
            vo.setConfigValue(c.getConfigValue());
            vo.setConfigType(c.getConfigType());
            vo.setRemark(c.getRemark());
            String group = c.getConfigType() == null ? "" : c.getConfigType();
            map.computeIfAbsent(group, k -> new ArrayList<>()).add(vo);
        }
        return map;
    }

    @Override
    public void updateValues(Map<String, String> values) {
        if (values == null) return;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey() == null) continue;
            Config c = new Config();
            c.setConfigValue(entry.getValue());
            configMapper.update(c, new LambdaUpdateWrapper<Config>()
                    .eq(Config::getConfigKey, entry.getKey()));
        }
    }

    @Override
    public AboutVO getAbout() {
        List<Config> list = configMapper.selectList(
                new LambdaQueryWrapper<Config>().in(Config::getConfigKey, ABOUT_KEYS));
        Map<String, String> m = new HashMap<>();
        for (Config c : list) m.put(c.getConfigKey(), c.getConfigValue());
        AboutVO vo = new AboutVO();
        vo.setSystemName(m.getOrDefault("system_name", ""));
        vo.setIcpNo(m.getOrDefault("icp_no", ""));
        vo.setSupportContact(m.getOrDefault("support_contact", ""));
        vo.setAboutUs(m.getOrDefault("about_us", ""));
        vo.setAppVersion(m.getOrDefault("app_version", ""));
        // 侧栏 logo：与 AboutVO 同源下发，省一次请求（V25 种子默认空串=未配置，前端回落默认标识）
        vo.setLogoUrl(m.getOrDefault("logo_url", ""));
        return vo;
    }
}
