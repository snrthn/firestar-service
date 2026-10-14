package com.firestar.controller;

import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.dto.OrgRequest;
import com.firestar.dto.OrgVO;
import com.firestar.service.OrgService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 组织架构（部门）管理接口（前端 /api/system/org/**，代理剥离 /api 后到 /system/org/**）。
 *
 * 【路径约定】后端路由不带 /api（同 MenuController）；除登录外默认需 JWT，
 * SecurityConfig 白名单未放行本路径，天然受保护。
 *
 * 【与用户模块的关系】sys_user.dept_id 指向本表；用户管理页的部门下拉
 * 将复用 GET /list 返回的全量树拍平结果。
 */
@RestController
@RequirePerm("org:manage")
@RequestMapping("/system/org")
public class OrgController {

    private final OrgService orgService;

    public OrgController(OrgService orgService) {
        this.orgService = orgService;
    }

    @GetMapping("/list")
    public Result<List<OrgVO>> list() {
        return Result.success(orgService.listTree());
    }

    @PostMapping
    public Result<Long> create(@RequestBody OrgRequest req) {
        return Result.success(orgService.create(req));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody OrgRequest req) {
        orgService.update(id, req);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        orgService.delete(id);
        return Result.success();
    }
}
