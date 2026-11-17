package com.firestar.ai.kb;

/**
 * 发布门决策结果（设计 §7.3）。
 *
 * @param allowed 是否放行发布
 * @param reason  拒绝/挂起原因（allowed=true 时可为 null）
 */
public record PublishDecision(boolean allowed, String reason) {

    /** 直接放行。 */
    public static PublishDecision allow() {
        return new PublishDecision(true, null);
    }

    /** 拒绝并给出原因。 */
    public static PublishDecision deny(String reason) {
        return new PublishDecision(false, reason);
    }
}
