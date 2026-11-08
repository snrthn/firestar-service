package com.firestar.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.common.Result;
import com.firestar.dto.FileVO;
import com.firestar.entity.User;
import com.firestar.exception.BizException;
import com.firestar.mapper.UserMapper;
import com.firestar.service.FileService;
import com.firestar.service.UserService;
import lombok.Getter;
import lombok.Setter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 个人中心补充接口（系统管理模块）。
 *
 * 【路径约定】同 AgreementController：后端路由不带 /api；SecurityConfig 白名单未放行，
 * 天然受 JWT 保护，登录即可访问。
 *
 * 【权限】本控制器不挂类级 @RequirePerm——头像上传刻意「免 file:manage」：
 * 普通用户也要能换自己的头像，不能要求人人有 file:manage。上传内部复用 FileService 落库，
 * 拿到 sys_file_storage.id 再写 sys_user.avatar_url，绕开 /files/upload 的 file:manage 门槛。
 */
@RestController
@RequestMapping("/system/profile")
public class ProfileController {

    private final FileService fileService;
    private final UserService userService;
    private final UserMapper userMapper;

    public ProfileController(FileService fileService, UserService userService, UserMapper userMapper) {
        this.fileService = fileService;
        this.userService = userService;
        this.userMapper = userMapper;
    }

    /** 当前用户头像信息（含 avatarUrl 文件ID；为空则前端昵称首字兜底）。 */
    @GetMapping("/me")
    public Result<ProfileMeVO> me() {
        User u = userMapper.selectById(currentUserId());
        if (u == null) {
            throw new IllegalStateException("当前登录用户不存在");
        }
        ProfileMeVO vo = new ProfileMeVO();
        vo.setAvatarUrl(u.getAvatarUrl());
        vo.setNickname(u.getNickname());
        return Result.success(vo);
    }

    /**
     * 上传并绑定头像（免 file:manage，登录即可）。
     * 内部复用 FileService 落库拿 sys_file_storage.id，再写 sys_user.avatar_url，
     * 从而普通用户也能换头像，不必走需要 file:manage 的 /files/upload。
     */
    @PostMapping("/avatar")
    public Result<ProfileMeVO> updateAvatar(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择头像文件");
        }
        Long userId = currentUserId();
        // bizType=avatar 便于文件管理处归类；fileType=IMAGE 是后端确知的分类（V206 前
        // file_type 字典压根没有 IMAGE 这个值，收窄后才补上）。这是内部显式分类、不是用户
        // 手选，故传 classify=true 保留既有行为；其它业务场景一律 ATTACHMENT。
        FileVO f = fileService.upload(file, "avatar", userId, "IMAGE", null, true);
        userService.updateAvatar(userId, String.valueOf(f.getId()));
        ProfileMeVO vo = new ProfileMeVO();
        vo.setAvatarUrl(String.valueOf(f.getId()));
        vo.setNickname(null);
        return Result.success(vo);
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

    /** 头像接口返回体（仅暴露前端需要的字段，不泄露账号细节） */
    @Getter
    @Setter
    public static class ProfileMeVO {
        /** 头像文件ID（sys_file_storage.id），为空则昵称首字兜底 */
        private String avatarUrl;
        private String nickname;
    }
}
