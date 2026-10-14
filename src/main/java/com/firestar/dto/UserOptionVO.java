package com.firestar.dto;

import lombok.Data;

/**
 * 用户下拉选项（负责人选择等场景）。
 * 只暴露 id + 姓名，避免把密码哈希等敏感字段带出接口。
 */
@Data
public class UserOptionVO {
    private Long id;
    private String nickname;
}
