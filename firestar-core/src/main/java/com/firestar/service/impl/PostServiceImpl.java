package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.firestar.dto.PostVO;
import com.firestar.dto.PostRequest;
import com.firestar.entity.Post;
import com.firestar.entity.User;
import com.firestar.exception.BizException;
import com.firestar.mapper.PostMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.service.PostService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 岗位管理实现。纪律同 RoleServiceImpl：走 BaseMapper，零手写 SQL；
 * 业务校验失败抛 BizException，由全局异常处理统一转 Result。
 */
@Service
public class PostServiceImpl implements PostService {

    /** 分页默认每页条数（前端分页器默认规格一致） */
    private static final long DEFAULT_PAGE_SIZE = 10L;
    /** 单页上限，与 MybatisPlusConfig.MAX_PAGE_SIZE 对齐 */
    private static final long MAX_PAGE_SIZE = 500L;
    /** 岗位编码格式：字母开头，可含字母、数字、下划线 */
    private static final String POST_CODE_PATTERN = "^[A-Za-z][A-Za-z0-9_]*$";

    private final PostMapper postMapper;
    private final UserMapper userMapper;

    public PostServiceImpl(PostMapper postMapper, UserMapper userMapper) {
        this.postMapper = postMapper;
        this.userMapper = userMapper;
    }

    @Override
    public IPage<PostVO> pagePosts(Integer pageNum, Integer pageSize, String postName, String status) {
        long current = pageNum == null || pageNum < 1 ? 1 : pageNum;
        long size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        // trim 必须先落到局部变量：wrapper.like(condition, column, postName.trim()) 的
        // 参数是渴求求值，condition=false 时 trim() 也已经执行过，null 直接 NPE
        String nameKw = postName == null || postName.isBlank() ? null : postName.trim();
        String statusEq = status == null || status.isBlank() ? null : status.trim();
        Page<Post> page = postMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<Post>()
                        .like(nameKw != null, Post::getPostName, nameKw)
                        .eq(statusEq != null, Post::getStatus, statusEq)
                        .orderByAsc(Post::getSortNo)
                        .orderByAsc(Post::getId));
        Page<PostVO> voPage = new Page<>(current, size);
        voPage.setTotal(page.getTotal());
        List<PostVO> vos = new ArrayList<>(page.getRecords().size());
        for (Post p : page.getRecords()) {
            vos.add(toVO(p));
        }
        voPage.setRecords(vos);
        return voPage;
    }

    @Override
    public Long createPost(PostRequest req) {
        Post post = new Post();
        applyForm(post, req, null);
        postMapper.insert(post);
        return post.getId();
    }

    @Override
    public void updatePost(Long id, PostRequest req) {
        Post exist = postMapper.selectById(id);
        if (exist == null) {
            throw new BizException("岗位不存在");
        }
        String code = req.getPostCode() == null ? "" : req.getPostCode().trim();
        if (!code.isEmpty() && !code.equals(exist.getPostCode())) {
            throw new BizException("岗位编码创建后不可修改");
        }
        Post post = new Post();
        post.setId(id);
        applyForm(post, req, id);
        postMapper.updateById(post);
    }

    @Override
    public void deletePost(Long id) {
        Post exist = postMapper.selectById(id);
        if (exist == null) {
            throw new BizException("岗位不存在");
        }
        Long userCount = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getPostId, id));
        if (userCount != null && userCount > 0) {
            throw new BizException("该岗位下有关联用户，请先调整用户岗位");
        }
        postMapper.deleteById(id);
    }

    @Override
    public List<PostVO> listOptions() {
        List<Post> list = postMapper.selectList(new LambdaQueryWrapper<Post>()
                .eq(Post::getStatus, "ENABLED")
                .orderByAsc(Post::getSortNo)
                .orderByAsc(Post::getId));
        List<PostVO> vos = new ArrayList<>(list.size());
        for (Post p : list) {
            vos.add(toVO(p));
        }
        return vos;
    }

    // ===== 私有辅助 =====

    private PostVO toVO(Post p) {
        PostVO vo = new PostVO();
        vo.setId(p.getId());
        vo.setPostCode(p.getPostCode());
        vo.setPostName(p.getPostName());
        vo.setSortNo(p.getSortNo());
        vo.setStatus(p.getStatus());
        vo.setRemark(p.getRemark());
        return vo;
    }

    /** 表单字段 → 实体（null 走 DDL 默认值：sortNo=0 / status=ENABLED）；selfId 为编辑中的岗位 ID（新增传 null） */
    private void applyForm(Post post, PostRequest req, Long selfId) {
        String postCode = req.getPostCode() == null ? "" : req.getPostCode().trim();
        String postName = req.getPostName() == null ? "" : req.getPostName().trim();
        if (postCode.isEmpty()) {
            throw new BizException("岗位编码不能为空");
        }
        if (!postCode.matches(POST_CODE_PATTERN)) {
            throw new BizException("岗位编码必须以字母开头，只能包含字母、数字、下划线");
        }
        if (postName.isEmpty()) {
            throw new BizException("岗位名称不能为空");
        }
        Long codeDup = postMapper.selectCount(new LambdaQueryWrapper<Post>()
                .eq(Post::getPostCode, postCode)
                .ne(selfId != null, Post::getId, selfId));
        if (codeDup != null && codeDup > 0) {
            throw new BizException("岗位编码已存在");
        }
        Long nameDup = postMapper.selectCount(new LambdaQueryWrapper<Post>()
                .eq(Post::getPostName, postName)
                .ne(selfId != null, Post::getId, selfId));
        if (nameDup != null && nameDup > 0) {
            throw new BizException("岗位名称已存在");
        }
        String status = req.getStatus() == null || req.getStatus().isBlank() ? "ENABLED" : req.getStatus();
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw new BizException("岗位状态仅支持 ENABLED / DISABLED");
        }
        post.setPostCode(postCode);
        post.setPostName(postName);
        post.setSortNo(req.getSortNo() == null ? 0 : req.getSortNo());
        post.setStatus(status);
        post.setRemark(req.getRemark());
    }
}
