package com.firestar.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.dto.LevelVO;
import com.firestar.dto.LevelRequest;

import java.util.List;

/**
 * 职级管理服务（V39 新增）。
 *
 * 关键业务规则（同 PostServiceImpl 纪律）：
 *  - levelCode 创建后不可改（同 username / roleCode / postCode）；
 *  - levelName 查重；
 *  - 删职级前校验关联用户（sys_user.level_code 有记录拒删）。
 */
public interface LevelService {

    /** 分页职级列表（levelName/levelCode 模糊、status 精确；默认 1/10，size 上限 500） */
    IPage<LevelVO> pageLevels(Integer pageNum, Integer pageSize, String levelName, String levelCode, String status);

    /** 创建职级（levelCode/levelName 查重；返回新职级 id） */
    Long createLevel(LevelRequest req);

    /** 修改职级（levelCode 不可改） */
    void updateLevel(Long id, LevelRequest req);

    /** 删除职级（关联用户 拒删） */
    void deleteLevel(Long id);

    /** 启用职级下拉（用户表单职级选择等场景；返回 levelCode 供节点配置直接引用） */
    List<LevelVO> listOptions();
}
