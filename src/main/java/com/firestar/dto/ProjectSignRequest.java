package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 立项签约请求（2026-10-22 虎哥拍板：签约 = 审批通过后的独立动作）。
 *
 * <p>前置条件由 Service 把关：approvalStatus = APPROVED 且 contractStatus = UNSIGNED；
 * 签约即终态（a 拍板：不可撤销），签约时间/签约人由服务端记录，调用方无权指定。</p>
 *
 * <p>V84（2026-10-30 虎哥拍板）：签约必须上传合同附件 —— 「项目毕竟还是需要有合同支撑的」。
 * contractFileIds 为已上传文件（sys_file_storage.id）列表，至少 1 个；
 * 签约时由 Service 统一改写 bizType/bizId 挂载到本项目（与发票附件 V62 同构）。</p>
 */
@Getter
@Setter
public class ProjectSignRequest {

    /** 最终合同金额（b 拍板：签约时可修正，建项填的是预估；null = 保持原值） */
    private BigDecimal contractAmount;

    /** 签约描述/签约留言（非必填，限 500 字） */
    private String contractDesc;

    /**
     * 合同附件文件 ID 列表（V84，**必填，至少 1 个**）。
     *
     * <p>前端先调 POST /files/upload（bizType='contract'，项目已存在故直接带 bizId=项目ID）
     * 拿 fileId 随本请求提交；后端统一挂载。缺失/空列表由 Service 硬卡。</p>
     */
    private List<Long> contractFileIds;
}
