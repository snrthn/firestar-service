package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.firestar.common.PermissionTreeUtil;
import com.firestar.dto.PermissionRequest;
import com.firestar.dto.PermissionVO;
import com.firestar.entity.Permission;
import com.firestar.entity.RolePermission;
import com.firestar.exception.BizException;
import com.firestar.mapper.PermissionMapper;
import com.firestar.mapper.RolePermissionMapper;
import com.firestar.service.PermissionService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 权限管理实现。纪律同 MenuServiceImpl：走 BaseMapper，零手写 SQL；
 * 业务校验失败抛 BizException，由全局异常处理统一转 Result。
 */
@Service
public class PermissionServiceImpl implements PermissionService {

    private final PermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;

    public PermissionServiceImpl(PermissionMapper permissionMapper, RolePermissionMapper rolePermissionMapper) {
        this.permissionMapper = permissionMapper;
        this.rolePermissionMapper = rolePermissionMapper;
    }

    @Override
    public List<PermissionVO> listTree() {
        // sys_permission 无 sort_no 列，按 id 升序（种子权限的插入序即展示序）
        List<Permission> perms = permissionMapper.selectList(new LambdaQueryWrapper<Permission>()
                .orderByAsc(Permission::getId));
        return PermissionTreeUtil.build(perms);
    }

    @Override
    public Long create(PermissionRequest req) {
        Permission perm = new Permission();
        applyForm(perm, req, null);
        permissionMapper.insert(perm);
        return perm.getId();
    }

    @Override
    public void update(Long id, PermissionRequest req) {
        Permission exist = permissionMapper.selectById(id);
        if (exist == null) {
            throw new BizException("权限不存在");
        }
        String permCode = req.getPermCode() == null ? "" : req.getPermCode().trim();
        if (!permCode.isEmpty() && !permCode.equals(exist.getPermCode())) {
            throw new BizException("权限编码创建后不可修改");
        }
        Permission perm = new Permission();
        perm.setId(id);
        applyForm(perm, req, id);
        // 防死循环：上级权限不能是自己，也不能是自己的子孙
        validateNoCycle(id, perm.getParentId());
        permissionMapper.updateById(perm);
    }

    @Override
    public void delete(Long id) {
        Permission exist = permissionMapper.selectById(id);
        if (exist == null) {
            throw new BizException("权限不存在");
        }
        Long childCount = permissionMapper.selectCount(new LambdaQueryWrapper<Permission>()
                .eq(Permission::getParentId, id));
        if (childCount != null && childCount > 0) {
            throw new BizException("该权限存在子权限，请先删除或移走子权限");
        }
        Long roleRefCount = rolePermissionMapper.selectCount(new LambdaQueryWrapper<RolePermission>()
                .eq(RolePermission::getPermissionId, id));
        if (roleRefCount != null && roleRefCount > 0) {
            throw new BizException("该权限已被角色引用，请先调整角色授权");
        }
        permissionMapper.deleteById(id);
    }

    private static final long DEFAULT_PAGE_SIZE = 10L;
    private static final long MAX_PAGE_SIZE = 500L;

    @Override
    public IPage<PermissionVO> pagePerms(Integer pageNum, Integer pageSize, String permName, String permCode, String status) {
        long current = pageNum == null || pageNum < 1 ? 1 : pageNum;
        long size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        // trim 落局部变量：同 RoleServiceImpl 的 MP 渴求求值防 NPE 纪律
        String nameKw = permName == null || permName.isBlank() ? null : permName.trim();
        String codeKw = permCode == null || permCode.isBlank() ? null : permCode.trim();
        String statusEq = status == null || status.isBlank() ? null : status.trim();
        Page<Permission> page = permissionMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<Permission>()
                        .like(nameKw != null, Permission::getPermName, nameKw)
                        .like(codeKw != null, Permission::getPermCode, codeKw)
                        .eq(statusEq != null, Permission::getStatus, statusEq)
                        .orderByAsc(Permission::getId));
        // 回填上級權限名稱：一次性查全表建 id→name map（避免 N+1）
        List<Permission> all = permissionMapper.selectList(new LambdaQueryWrapper<Permission>());
        Map<Long, String> nameById = all.stream()
                .collect(Collectors.toMap(Permission::getId, Permission::getPermName, (a, b) -> a));
        Page<PermissionVO> voPage = new Page<>(current, size);
        voPage.setTotal(page.getTotal());
        List<PermissionVO> vos = new ArrayList<>(page.getRecords().size());
        for (Permission p : page.getRecords()) {
            PermissionVO vo = new PermissionVO();
            vo.setId(p.getId());
            vo.setParentId(p.getParentId());
            vo.setPermCode(p.getPermCode());
            vo.setPermName(p.getPermName());
            vo.setResourceType(p.getResourceType());
            vo.setActionType(p.getActionType());
            vo.setStatus(p.getStatus());
            if (p.getParentId() != null && p.getParentId() != 0) {
                vo.setParentName(nameById.get(p.getParentId()));
            }
            vos.add(vo);
        }
        voPage.setRecords(vos);
        return voPage;
    }

    // ===== 私有辅助 =====

    /** 表单字段 → 实体；selfId 为编辑中的权限 ID（新增传 null） */
    private void applyForm(Permission perm, PermissionRequest req, Long selfId) {
        String permCode = req.getPermCode() == null ? "" : req.getPermCode().trim();
        String permName = req.getPermName() == null ? "" : req.getPermName().trim();
        if (permCode.isEmpty()) {
            throw new BizException("权限编码不能为空");
        }
        if (permName.isEmpty()) {
            throw new BizException("权限名称不能为空");
        }
        Long parentId = req.getParentId() == null ? 0L : req.getParentId();
        if (parentId != 0) {
            Permission parent = permissionMapper.selectById(parentId);
            if (parent == null) {
                throw new BizException("上级权限不存在");
            }
        }
        Long dupCount = permissionMapper.selectCount(new LambdaQueryWrapper<Permission>()
                .eq(Permission::getPermCode, permCode)
                .ne(selfId != null, Permission::getId, selfId));
        if (dupCount != null && dupCount > 0) {
            throw new BizException("权限编码已存在");
        }
        String status = req.getStatus() == null || req.getStatus().isBlank() ? "ENABLED" : req.getStatus();
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw new BizException("权限状态仅支持 ENABLED / DISABLED");
        }
        perm.setPermCode(permCode);
        perm.setPermName(permName);
        perm.setResourceType(req.getResourceType() == null || req.getResourceType().isBlank()
                ? "API" : req.getResourceType().trim());
        perm.setActionType(req.getActionType() == null || req.getActionType().isBlank()
                ? "EXECUTE" : req.getActionType().trim());
        perm.setParentId(parentId);
        perm.setStatus(status);
    }

    /**
     * 防死循环校验：上级权限链上溯遇到自身即拒（数据层面已有环时用 visited 兜底跳出）。
     * create 场景 id 为 null，由调用方跳过。
     */
    private void validateNoCycle(Long id, Long parentId) {
        if (id == null || parentId == null || parentId == 0) {
            return;
        }
        if (parentId.equals(id)) {
            throw new BizException("上级权限不能是权限自身");
        }
        Set<Long> visited = new HashSet<>();
        Long cursor = parentId;
        while (cursor != null && cursor != 0) {
            if (cursor.equals(id)) {
                throw new BizException("上级权限不能设置为自己的子权限，会造成死循环");
            }
            if (!visited.add(cursor)) {
                break;
            }
            Permission parent = permissionMapper.selectById(cursor);
            if (parent == null) {
                break;
            }
            cursor = parent.getParentId();
        }
    }
}
