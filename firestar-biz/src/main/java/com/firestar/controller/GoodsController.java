package com.firestar.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.firestar.common.MasterDataNoUtil;
import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.dto.GoodsOptionVO;
import com.firestar.entity.Goods;
import com.firestar.exception.BizException;
import com.firestar.mapper.GoodsMapper;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 货物只读接口（前端 /api/goods/**，代理剥离 /api 后到 /goods/**）。
 *
 * <p><b>V71 一期只有下拉接口，没有 CRUD。</b>虎哥 2026-10-26 拍板：本期只做
 * 「货物主表 + 历史回填 + 项目货物行关联下拉」，不做独立管理页面、不种菜单/权限点。
 * 因此这里不写 @RequirePerm —— 与 CustomerController / ItemController 同理：货物名是立项人
 * 本来就要看的基础数据，且已在 JWT 鉴权之后（PermissionInterceptor 对无注解接口仅做登录校验）。
 * 后续若开放货物增删改，必须另起一套 goods:manage 权限点的接口。</p>
 *
 * <p>建档入口只有两个（目前）：V71 迁移对存量货物名的回填，以及立项/编辑项目时
 * 按名称 upsert（ProjectServiceImpl 内 resolveGoods）。</p>
 */
@RestController
@RequestMapping("/goods")
public class GoodsController {

    private final GoodsMapper goodsMapper;

    public GoodsController(GoodsMapper goodsMapper) {
        this.goodsMapper = goodsMapper;
    }

    /**
     * 货物下拉列表（ENABLED 且未逻辑删除，名称升序）。
     *
     * @param keyword 可选，货物名称模糊匹配
     * @param limit   可选，返回条数上限（默认 200，防异常数据把下拉撑爆）
     */
    @GetMapping("/options")
    public Result<List<GoodsOptionVO>> options(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "200") Integer limit) {
        LambdaQueryWrapper<Goods> q = new LambdaQueryWrapper<Goods>()
                .eq(Goods::getStatus, "ENABLED")
                .orderByAsc(Goods::getGoodsName);
        if (StringUtils.hasText(keyword)) {
            q.like(Goods::getGoodsName, keyword.trim());
        }
        // last() 拼接会绕过参数绑定，这里 limit 是 Integer 且已受 defaultValue 约束，
        // 仍做一次范围夹紧再拼接，防止被外部塞入非法值触发 SQL 注入/巨大结果集。
        int safeLimit = (limit == null || limit <= 0) ? 200 : Math.min(limit, 500);
        q.last("LIMIT " + safeLimit);

        List<GoodsOptionVO> list = goodsMapper.selectList(q).stream().map(g -> {
            GoodsOptionVO vo = new GoodsOptionVO();
            vo.setId(g.getId());
            vo.setGoodsNo(g.getGoodsNo());
            vo.setGoodsName(g.getGoodsName());
            return vo;
        }).toList();
        return Result.success(list);
    }

    /** 货物档案分页列表（V218 项目页档案管理抽屉）。 */
    @GetMapping("/list")
    @RequirePerm("goods:manage")
    public Result<IPage<Goods>> list(@RequestParam(defaultValue = "1") Integer pageNum,
                                     @RequestParam(defaultValue = "10") Integer pageSize,
                                     @RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<Goods> q = new LambdaQueryWrapper<Goods>();
        if (StringUtils.hasText(keyword)) {
            q.and(w -> w.like(Goods::getGoodsName, keyword.trim())
                    .or().like(Goods::getGoodsNo, keyword.trim()));
        }
        q.orderByDesc(Goods::getCreatedAt);
        return Result.success(goodsMapper.selectPage(new Page<>(pageNum, pageSize), q));
    }

    /** 货物档案新增 / 编辑。 */
    @PostMapping("/save")
    @RequirePerm("goods:manage")
    public Result<Goods> save(@RequestBody Goods req) {
        if (!StringUtils.hasText(req.getGoodsName())) {
            throw new BizException("货物名称不能为空");
        }
        req.setGoodsName(req.getGoodsName().trim());
        if (req.getId() != null) {
            goodsMapper.updateById(req);
            return Result.success(goodsMapper.selectById(req.getId()));
        }
        if (req.getStatus() == null || req.getStatus().isBlank()) req.setStatus("ENABLED");
        Goods exists = goodsMapper.selectOne(new LambdaQueryWrapper<Goods>()
                .eq(Goods::getGoodsName, req.getGoodsName()).last("LIMIT 1"));
        if (exists != null) throw new BizException("货物名称已存在：" + req.getGoodsName());
        goodsMapper.insert(req);
        req.setGoodsNo(MasterDataNoUtil.goodsNo(req.getId()));
        goodsMapper.updateById(req);
        return Result.success(req);
    }
}
