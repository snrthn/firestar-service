package com.firestar.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.common.Result;
import com.firestar.dto.CustomerOptionVO;
import com.firestar.entity.Customer;
import com.firestar.mapper.CustomerMapper;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 客户只读接口（前端 /api/customer/**，代理剥离 /api 后到 /customer/**）。
 *
 * <p><b>V68 一期只有下拉接口，没有 CRUD。</b>虎哥 2026-10-26 拍板：本期只做
 * 「客户主表 + 历史回填 + 项目表单关联下拉」，不做独立管理页面、不种菜单/权限点。
 * 因此这里不写 @RequirePerm —— 与 ItemController 同理：客户名是立项人本来就要看的
 * 基础数据，且已在 JWT 鉴权之后（PermissionInterceptor 对无注解接口仅做登录校验）。
 * 后续若开放客户增删改，必须另起一套 customer:manage 权限点的接口。</p>
 *
 * <p>建档入口只有两个（目前）：V68 迁移对存量项目的回填，以及立项/编辑项目时
 * 按名称 upsert（ProjectServiceImpl.resolveCustomer）。</p>
 */
@RestController
@RequestMapping("/customer")
public class CustomerController {

    private final CustomerMapper customerMapper;

    public CustomerController(CustomerMapper customerMapper) {
        this.customerMapper = customerMapper;
    }

    /**
     * 客户下拉列表（ENABLED 且未逻辑删除，名称升序）。
     *
     * @param keyword 可选，客户名称模糊匹配
     * @param limit   可选，返回条数上限（默认 200，防异常数据把下拉撑爆）
     */
    @GetMapping("/options")
    public Result<List<CustomerOptionVO>> options(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "200") Integer limit) {
        LambdaQueryWrapper<Customer> q = new LambdaQueryWrapper<Customer>()
                .eq(Customer::getStatus, "ENABLED")
                .orderByAsc(Customer::getCustomerName);
        if (StringUtils.hasText(keyword)) {
            q.like(Customer::getCustomerName, keyword.trim());
        }
        // last() 拼接会绕过参数绑定，这里 limit 是 Integer 且已受 defaultValue 约束，
        // 仍做一次范围夹紧再拼接，防止被外部塞入非法值触发 SQL 注入/巨大结果集。
        int safeLimit = (limit == null || limit <= 0) ? 200 : Math.min(limit, 500);
        q.last("LIMIT " + safeLimit);

        List<CustomerOptionVO> list = customerMapper.selectList(q).stream().map(c -> {
            CustomerOptionVO vo = new CustomerOptionVO();
            vo.setId(c.getId());
            vo.setCustomerNo(c.getCustomerNo());
            vo.setCustomerName(c.getCustomerName());
            vo.setContactPhone(c.getContactPhone());
            return vo;
        }).toList();
        return Result.success(list);
    }
}
