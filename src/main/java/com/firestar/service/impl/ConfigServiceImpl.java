package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.firestar.dto.AboutVO;
import com.firestar.dto.ConfigCreateRequest;
import com.firestar.dto.ConfigItemVO;
import com.firestar.entity.Config;
import com.firestar.exception.BizException;
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

    /** 特性开关白名单：登录用户可读取的布尔型功能开关（不暴露管理员配置细节）。缺省 "false"。 */
    private static final List<String> FLAG_KEYS = Arrays.asList("dashboard_status_visible");

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

    @Override
    public String getConfigValue(String key) {
        if (key == null) return null;
        Config c = configMapper.selectOne(
                new LambdaQueryWrapper<Config>().eq(Config::getConfigKey, key));
        return c == null ? null : c.getConfigValue();
    }

    @Override
    public Map<String, String> getFlags() {
        List<Config> list = configMapper.selectList(
                new LambdaQueryWrapper<Config>().in(Config::getConfigKey, FLAG_KEYS));
        // 白名单内每一项先给缺省 "false"（隐藏），再被已存在的配置值覆盖。
        Map<String, String> m = new HashMap<>();
        for (String k : FLAG_KEYS) m.put(k, "false");
        for (Config c : list) {
            m.put(c.getConfigKey(), c.getConfigValue() == null ? "false" : c.getConfigValue());
        }
        return m;
    }

    @Override
    public void setConfigValue(String key, String value) {
        if (key == null) return;
        Config c = new Config();
        c.setConfigValue(value);
        configMapper.update(c, new LambdaUpdateWrapper<Config>().eq(Config::getConfigKey, key));
    }

    /** 配置键格式：字母/数字/下划线/短横线，2-64 位（防止乱键污染 KV 空间） */
    private static final java.util.regex.Pattern KEY_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{2,64}$");

    @Override
    public void createConfig(ConfigCreateRequest req) {
        if (req == null) {
            throw new BizException("请求不能为空");
        }
        String key = req.getConfigKey() == null ? null : req.getConfigKey().trim();
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new BizException("配置键格式不正确：仅支持字母/数字/下划线/短横线，2-64 位");
        }
        String value = req.getConfigValue();
        if (value == null || value.isBlank()) {
            throw new BizException("配置值不能为空");
        }
        Long cnt = configMapper.selectCount(
                new LambdaQueryWrapper<Config>().eq(Config::getConfigKey, key));
        if (cnt != null && cnt > 0) {
            throw new BizException("配置键已存在：" + key);
        }
        Config c = new Config();
        c.setConfigKey(key);
        c.setConfigValue(value);
        // 自定义键统一归入 custom 分组（分组渲染见参数设置页键值管理）
        c.setConfigType("custom");
        c.setRemark(req.getRemark());
        configMapper.insert(c);
    }

    @Override
    public void updateConfigItem(ConfigCreateRequest req) {
        if (req == null) {
            throw new BizException("请求不能为空");
        }
        String key = req.getConfigKey() == null ? null : req.getConfigKey().trim();
        if (key == null || key.isEmpty()) {
            throw new BizException("配置键不能为空");
        }
        String value = req.getConfigValue();
        if (value == null || value.isBlank()) {
            throw new BizException("配置值不能为空");
        }
        Long cnt = configMapper.selectCount(
                new LambdaQueryWrapper<Config>().eq(Config::getConfigKey, key));
        if (cnt == null || cnt == 0) {
            throw new BizException("配置键不存在：" + key);
        }
        Config c = new Config();
        c.setConfigValue(value);
        c.setRemark(req.getRemark());
        configMapper.update(c, new LambdaUpdateWrapper<Config>().eq(Config::getConfigKey, key));
    }
}
