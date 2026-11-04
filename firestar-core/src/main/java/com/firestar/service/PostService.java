package com.firestar.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.dto.PostVO;
import com.firestar.dto.PostRequest;

import java.util.List;

/**
 * 岗位管理服务。
 *
 * 关键业务规则：
 *  - postCode 创建后不可改（同 username / roleCode 纪律）；
 *  - postName 查重；
 *  - 删岗位前校验关联用户（sys_user.post_id 有记录拒删）。
 */
public interface PostService {

    /** 分页岗位列表（postName 模糊、status 精确；默认 1/10，size 上限 500） */
    IPage<PostVO> pagePosts(Integer pageNum, Integer pageSize, String postName, String status);

    /** 创建岗位（postCode/postName 查重；返回新岗位 id） */
    Long createPost(PostRequest req);

    /** 修改岗位（postCode 不可改） */
    void updatePost(Long id, PostRequest req);

    /** 删除岗位（关联用户 拒删） */
    void deletePost(Long id);

    /** 启用岗位下拉（创建用户时选择，非必填） */
    List<PostVO> listOptions();
}
