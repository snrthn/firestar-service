package com.firestar.dto;

import lombok.Data;

import java.util.List;

/**
 * 角色分配权限请求（整体替换语义：提交的 permIds 即该角色的最终权限集合）。
 */
@Data
public class RolePermRequest {

    /** 权限 ID 集合 → sys_permission.id（空集合 = 清空该角色权限） */
    private List<Long> permIds;
}
