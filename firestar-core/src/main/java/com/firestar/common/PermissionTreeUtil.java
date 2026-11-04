package com.firestar.common;

import com.firestar.dto.PermissionVO;
import com.firestar.entity.Permission;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限树构建工具（菜单树 MenuTreeUtil 的 permission 版本）。
 * 单处维护父子挂接规则：parentId 为 0 / null / 不在集合内的视为根节点，
 * children 顺序即调用方传入顺序（Service 层按 id 升序排好再传入）。
 * 工具本身不碰数据库。
 */
public final class PermissionTreeUtil {

    private PermissionTreeUtil() {
    }

    public static List<PermissionVO> build(List<Permission> perms) {
        Map<Long, PermissionVO> voMap = new LinkedHashMap<>();
        for (Permission p : perms) {
            PermissionVO vo = new PermissionVO();
            vo.setId(p.getId());
            vo.setParentId(p.getParentId());
            vo.setPermCode(p.getPermCode());
            vo.setPermName(p.getPermName());
            vo.setResourceType(p.getResourceType());
            vo.setActionType(p.getActionType());
            vo.setStatus(p.getStatus());
            voMap.put(p.getId(), vo);
        }
        List<PermissionVO> roots = new ArrayList<>();
        for (PermissionVO vo : voMap.values()) {
            if (vo.getParentId() != null && vo.getParentId() != 0 && voMap.containsKey(vo.getParentId())) {
                PermissionVO parent = voMap.get(vo.getParentId());
                if (parent.getChildren() == null) {
                    parent.setChildren(new ArrayList<>());
                }
                parent.getChildren().add(vo);
            } else {
                roots.add(vo);
            }
        }
        return roots;
    }
}
