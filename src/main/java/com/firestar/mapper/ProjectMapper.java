package com.firestar.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.firestar.entity.Project;
import org.apache.ibatis.annotations.Mapper;

/**
 * 项目 Mapper。
 *
 * 【V1 纪律】一律继承 BaseMapper，零手写 SQL。
 * 需要非 CRUD 查询时在 XML 里写（mapper-locations 已配 classpath 下的 mapper 目录），
 * 禁止在 @Select 注解里拼 SQL（不便于审阅与统一拦截器改写）。
 *
 * ⚠️ 注释里禁止出现 glob 星号路径：{@code **}{@code /} 序列会提前闭合块注释，
 * 导致后续中文被当成代码解析，报"非法字符"。这是踩过的坑。
 */
@Mapper
public interface ProjectMapper extends BaseMapper<Project> {
}
