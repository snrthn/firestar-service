package com.firestar.controller;

import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.dto.AboutVO;
import com.firestar.dto.ConfigItemVO;
import com.firestar.service.ConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 参数设置接口（系统管理模块）。
 *
 * 【路径约定】同 DictController：后端路由不带 /api；SecurityConfig 白名单未放行本路径，天然需 JWT。
 *
 * 【权限】/group、/（更新）= @RequirePerm("config:manage")；/about = 登录即可（关于我们弹窗对全部登录用户可见）。
 */
@RestController
@RequestMapping("/system/config")
public class ConfigController {

    private final ConfigService configService;

    public ConfigController(ConfigService configService) {
        this.configService = configService;
    }

    /** 按 config_type 分组返回配置（管理员参数设置页表单渲染，不暴露裸 key 作为存储细节） */
    @RequirePerm("config:manage")
    @GetMapping("/group")
    public Result<Map<String, List<ConfigItemVO>>> group() {
        return Result.success(configService.getGrouped());
    }

    /** 批量更新配置值（管理员，前端以 key→value 扁平 map 提交） */
    @RequirePerm("config:manage")
    @PutMapping
    public Result<Void> update(@RequestBody Map<String, String> values) {
        configService.updateValues(values);
        return Result.success();
    }

    /** 关于我们弹窗数据（登录用户可见，无需 config:manage） */
    @GetMapping("/about")
    public Result<AboutVO> about() {
        return Result.success(configService.getAbout());
    }
}
