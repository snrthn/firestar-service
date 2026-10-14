package com.firestar.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.dto.UserOptionVO;
import com.firestar.dto.UserRequest;
import com.firestar.dto.UserVO;

import java.util.List;

/**
 * 用户管理服务。
 * 定位：用户管理模块（Phase 1）的 CRUD + 组织架构等模块依赖的只读选项查询。
 *
 * 关键业务规则：
 *  - username 创建后不可改（账号是登录标识，同 role_code / typeCode 纪律）；
 *  - 新用户密码走 BCrypt，force_change_password=1（首次登录强制改密，与 admin 账号一致）；
 *  - 拥有 ROLE_ADMIN 角色的用户与当前登录用户自己禁删（保住管理员入口）；
 *  - V1 不提供「重置密码」（独立改密流程，后续单独做）。
 */
public interface UserService {

    /** 启用用户的下拉选项（id + 姓名，按 id 升序） */
    List<UserOptionVO> listOptions();

    /** 分页用户列表（username/nickname 模糊、status/deptId 精确；deptName/roleNames 批量回填） */
    IPage<UserVO> pageUsers(Integer pageNum, Integer pageSize, String username, String nickname, String status, Long deptId);

    /** 创建用户（username 查重 + BCrypt + 角色关联；返回新用户 id） */
    Long createUser(UserRequest req);

    /** 修改用户（username 不可改；不提供改密码，密码字段一律忽略） */
    void updateUser(Long id, UserRequest req);

    /** 删除用户（ROLE_ADMIN 持有者 / 自己 拒删；连带清理 sys_user_role） */
    void deleteUser(Long id);
}
