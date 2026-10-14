package com.firestar.dto;

import lombok.Data;

import java.util.List;

/**
 * 角色分配菜单请求（整体替换语义，空集合 = 清空）。
 * 与 RolePermRequest 对称：角色授权拆成「权限」与「菜单」两棵树分别整体替换。
 */
@Data
public class RoleMenuRequest {

    /** 菜单 ID 集合 → sys_menu.id（写操作整体替换） */
    private List<Long> menuIds;
}
