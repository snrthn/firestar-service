package com.firestar.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.firestar.entity.Post;
import org.apache.ibatis.annotations.Mapper;

/**
 * V10 新增。V1 纪律：一律继承 BaseMapper，零手写 SQL。
 */
@Mapper
public interface PostMapper extends BaseMapper<Post> {
}
