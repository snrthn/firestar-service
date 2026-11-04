package com.firestar.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.dto.DeployResult;
import com.firestar.dto.ProcessDefVersionVO;
import com.firestar.entity.ProcessDef;
import com.firestar.entity.User;
import com.firestar.mapper.UserMapper;
import com.firestar.service.ProcessDefService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 流程定义管理（Step 2 配置节点）。
 *
 * <p>权限：类级 {@code process:view}（读接口默认需此权限）；
 * 写接口（新建/更新/部署/停用）方法级 {@code process:manage} 覆盖类级。</p>
 *
 * <p>路径约定：后端不含 /api，前缀由前端 vite proxy / nginx 剥离（同其他模块）。</p>
 */
@RestController
@RequestMapping("/process-defs")
@RequirePerm("process:view")
public class ProcessDefController {

    private final ProcessDefService service;
    private final UserMapper userMapper;

    public ProcessDefController(ProcessDefService service, UserMapper userMapper) {
        this.service = service;
        this.userMapper = userMapper;
    }

    @GetMapping
    public Result<List<ProcessDef>> list() {
        return Result.success(service.list());
    }

    /**
     * 分页查询（对齐 /system/role/page 范式）。
     * 字面路径 /page 优先于 /{id} 模板匹配，无冲突。
     */
    @GetMapping("/page")
    public Result<IPage<ProcessDef>> page(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String processCode,
            @RequestParam(required = false) String processName,
            @RequestParam(required = false) String status) {
        return Result.success(service.page(pageNum, pageSize, processCode, processName, status));
    }

    @GetMapping("/{id}")
    public Result<ProcessDef> detail(@PathVariable Long id) {
        return Result.success(service.detail(id));
    }

    @RequirePerm("process:create")
    @PostMapping
    public Result<ProcessDef> create(@RequestBody ProcessDef def) {
        def.setCreatedBy(currentUserId());
        def.setUpdatedBy(currentUserId());
        return Result.success(service.create(def));
    }

    @RequirePerm("process:edit")
    @PutMapping("/{id}")
    public Result<ProcessDef> update(@PathVariable Long id, @RequestBody ProcessDef def) {
        def.setUpdatedBy(currentUserId());
        return Result.success(service.update(id, def));
    }

    @RequirePerm("process:deploy")
    @PostMapping("/{id}/deploy")
    public Result<DeployResult> deploy(@PathVariable Long id) {
        return Result.success(service.deploy(id));
    }

    @RequirePerm("process:toggle")
    @PostMapping("/{id}/disable")
    public Result<ProcessDef> toggleStatus(@PathVariable Long id) {
        return Result.success(service.toggleStatus(id));
    }

    @GetMapping("/{id}/versions")
    public Result<List<ProcessDefVersionVO>> versions(@PathVariable Long id) {
        return Result.success(service.versions(id));
    }

    private Long currentUserId() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username).select(User::getId));
        if (u == null) {
            throw new IllegalStateException("当前登录用户不存在");
        }
        return u.getId();
    }
}
