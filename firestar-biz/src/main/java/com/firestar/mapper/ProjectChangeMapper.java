package com.firestar.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.firestar.entity.ProjectChange;
import org.apache.ibatis.annotations.Mapper;

/**
 * 变更单 Mapper（V220）。V1 纪律：继承 BaseMapper，零手写 SQL。
 */
@Mapper
public interface ProjectChangeMapper extends BaseMapper<ProjectChange> {
}
