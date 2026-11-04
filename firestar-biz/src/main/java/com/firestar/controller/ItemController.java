package com.firestar.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.common.Result;
import com.firestar.common.SecurityContextUtils;
import com.firestar.entity.Item;
import com.firestar.entity.Project;
import com.firestar.mapper.ItemMapper;
import com.firestar.mapper.ProjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;

/**
 * 货物明细接口（前端 /api/item/**，代理剥离 /api 后到 /item/**）。
 *
 * <p>货物（fin_item）是项目的子聚合，目前仅提供「按项目拉取」的只读接口，
 * 供发货申请表单选择货物、预填剩余可发量。
 * <b>2026-10-27 安全修复（P0-3）</b>：原实现无 @RequirePerm、仅按 projectId 过滤，
 * 任意登录用户可遍历自增 projectId 爬取他方货物单价/合同额。现改为与 ProjectServiceImpl
 * 同口径的归属校验（ROLE_ADMIN 或项目 applicantUsername == 当前用户），非本人按 404 返回。</p>
 */
@RestController
@RequestMapping("/item")
public class ItemController {

    private final ItemMapper itemMapper;
    private final ProjectMapper projectMapper;

    public ItemController(ItemMapper itemMapper, ProjectMapper projectMapper) {
        this.itemMapper = itemMapper;
        this.projectMapper = projectMapper;
    }

    /**
     * 按项目拉取货物明细（按 id 升序；含 shippedQty 供前端预校验超发）。
     * 安全：归属校验，非项目负责人/管理员返回 404，杜绝 projectId 遍历泄露商业数据。
     */
    @GetMapping("/list")
    public Result<List<Item>> list(@RequestParam Long projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在或无权访问：" + projectId);
        }
        String username = SecurityContextUtils.currentUsername();
        if (!SecurityContextUtils.isAdmin()
                && !Objects.equals(project.getApplicantUsername(), username)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在或无权访问：" + projectId);
        }
        List<Item> items = itemMapper.selectList(new LambdaQueryWrapper<Item>()
                .eq(Item::getProjectId, projectId)
                .orderByAsc(Item::getId));
        return Result.success(items);
    }
}
