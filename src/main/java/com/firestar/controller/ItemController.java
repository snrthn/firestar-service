package com.firestar.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.common.Result;
import com.firestar.entity.Item;
import com.firestar.mapper.ItemMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 货物明细接口（前端 /api/item/**，代理剥离 /api 后到 /item/**）。
 *
 * <p>货物（fin_item）是项目的子聚合，目前仅提供「按项目拉取」的只读接口，
 * 供发货申请表单选择货物、预填剩余可发量。无 @RequirePerm：货物只读且属于用户可见的项目，
 * 仅需在 JWT 鉴权内（PermissionInterceptor 对无注解接口放行具体权限校验）。</p>
 */
@RestController
@RequestMapping("/item")
public class ItemController {

    private final ItemMapper itemMapper;

    public ItemController(ItemMapper itemMapper) {
        this.itemMapper = itemMapper;
    }

    /** 按项目拉取货物明细（按 id 升序；含 shippedQty 供前端预校验超发） */
    @GetMapping("/list")
    public Result<List<Item>> list(@RequestParam Long projectId) {
        List<Item> items = itemMapper.selectList(new LambdaQueryWrapper<Item>()
                .eq(Item::getProjectId, projectId)
                .orderByAsc(Item::getId));
        return Result.success(items);
    }
}
