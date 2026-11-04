package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 岗位（V10 新增）。
 * 与角色不同，岗位不走「根基角色禁止改删」规则；删除前校验是否有关联用户
 * （sys_user.post_id 引用，单岗设计）。
 */
@Getter
@Setter
@TableName(value = "sys_post", autoResultMap = true)
public class Post extends AuditedEntity {

    private static final long serialVersionUID = 1L;

    /** 岗位编码（创建后不可改，同 username / roleCode 纪律） */
    private String postCode;

    /** 岗位名称 */
    private String postName;

    /** 排序（同级内数字越小越靠前） */
    private Integer sortNo;

    /** 状态（字典 common_status） */
    private String status;

    /** 备注 */
    private String remark;
}
