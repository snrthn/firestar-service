package com.firestar.ai.egress;

/** 出域字段脱敏动作（设计 §7.1）。 */
public enum MaskAction {
    /** 放行（如金额，需由工具层保证当前用户已授权可见） */
    PASS,
    /** 加盐哈希稳定 token（如税号 / 客户名），跨轮可指代但不泄露原值 */
    PSEUDONYMIZE,
    /** 部分遮盖，保留末 N 位（如银行账号 / 手机号） */
    PARTIAL_MASK,
    /** 整段脱敏 */
    REDACT
}
