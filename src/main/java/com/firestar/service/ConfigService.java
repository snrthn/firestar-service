package com.firestar.service;

import com.firestar.dto.AboutVO;
import com.firestar.dto.ConfigItemVO;

import java.util.List;
import java.util.Map;

/**
 * 参数设置服务：按 config_type 分组返回结构化配置（不暴露裸 key 作为存储细节）、
 * 批量更新、关于我们只读聚合。
 */
public interface ConfigService {

    /** 按 config_type 分组返回全部配置项（管理员参数设置页用） */
    Map<String, List<ConfigItemVO>> getGrouped();

    /** 批量更新配置值（前端以 key→value 扁平 map 提交，按 config_key 匹配） */
    void updateValues(Map<String, String> values);

    /** 关于我们弹窗聚合数据（登录用户可见，无需 config:manage） */
    AboutVO getAbout();
}
