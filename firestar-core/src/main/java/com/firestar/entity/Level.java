package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 职级（V39 新增）。
 *
 * <p>与岗位（sys_post）的区别：岗位答「干什么活」，职级答「哪个审批层级」——两个维度。
 * levelCode 创建后不可改（同 username / roleCode / postCode 纪律）；
 * levelNo 为数值层级（1=最低，逐级+1），供「高一级」语义比较（字典排序字段不承载比较语义）。</p>
 *
 * <p>消费方（Step 3）：P3 流程节点类型 LEVEL 的运行时解析——
 * {@code sys_process_def.node_list} 里配 levelCode，LevelResolver 按 levelCode 找人。</p>
 */
@Getter
@Setter
@TableName(value = "sys_level", autoResultMap = true)
public class Level extends AuditedEntity {

    private static final long serialVersionUID = 1L;

    /** 职级编码（创建后不可改，如 L1 / L2 / L3） */
    private String levelCode;

    /** 职级名称（如 专员 / 主管 / 经理） */
    private String levelName;

    /** 层级数值（1=最低，逐级+1；供「高一级」比较） */
    private Integer levelNo;

    /** 排序（同级内数字越小越靠前） */
    private Integer sortNo;

    /** 状态（字典 common_status） */
    private String status;

    /** 备注 */
    private String remark;
}
