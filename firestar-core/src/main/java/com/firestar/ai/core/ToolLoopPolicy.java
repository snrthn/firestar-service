package com.firestar.ai.core;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 受控多步工具链策略（供应商无关，OpenAI / Anthropic / DeepSeek 共用）。
 *
 * <p>此前两个 provider 都只在<b>首轮</b>下发 tools：模型一轮里并行发出多个 tool_calls，
 * 执行后下一轮不带工具、只准基于结果总结——所以"先查项目→再查该项目发货→再核销"这类
 * 依赖上一步结果的串行链路根本走不通，助手只能答单跳问题。</p>
 *
 * <p>本策略放开续轮 tools 让模型能按结果决定下一步，但加了三层护栏，避免从"能力太弱"
 * 变成"放养式 agent"：</p>
 * <ol>
 *   <li><b>步数预算</b>：最多 {@link #MAX_TOOL_ROUNDS} 轮工具调用，再留 1 轮纯总结，总轮不超 5；
 *   <li><b>重复调用检测</b>：同一 "工具名+参数" 签名已出现过就不再下发工具，逼模型基于已有结果收尾，
 *       防模型在错误结果上原地打转烧 token；
 *   <li><b>权限闸不变</b>：每步工具仍经 ToolExecutor 按 requiredPerm 校验，放开续轮不绕过权限。
 * </ol>
 *
 * <p>纯函数、无状态，便于单测锁定边界；provider 只负责把每轮解析出的调用签名喂进来。</p>
 */
public final class ToolLoopPolicy {

    /** 最多几轮下发 tools（之后强制总结轮）。总轮预算 = MAX_TOOL_ROUNDS + 1。 */
    public static final int MAX_TOOL_ROUNDS = 4;

    private ToolLoopPolicy() {
    }

    /**
     * 第 {@code round} 轮（从 0 计）是否下发 tools。
     * 前 {@value #MAX_TOOL_ROUNDS} 轮允许工具调用，之后只总结。
     */
    public static boolean shouldOfferTools(int round, boolean toolsAvailable) {
        return toolsAvailable && round < MAX_TOOL_ROUNDS;
    }

    /**
     * 本轮的调用里是否存在"此前没出现过"的新调用（Same tool + Same args 视为重复）。
     *
     * <p><b>为什么不在这里过滤掉重复项不执行：</b>OpenAI / Anthropic 都要求 assistant 的
     * tool_calls 与回灌的 tool 结果<b>严格一一配对</b>，跳过某个 tool_call 不执行会破坏配对、
     * 直接被供应商 400 拒。所以本轮所有调用照常执行、照常回灌；重复检测只用于
     * {@code hasFreshCalls == false} 时停止下一轮下发 tools，逼模型基于已有结果总结。</p>
     */
    public static boolean hasFreshCalls(List<String> roundSignatures, Set<String> seen) {
        for (String sig : roundSignatures) {
            if (sig != null && !seen.contains(sig)) {
                return true;
            }
        }
        return false;
    }

    /** 把本轮签名全部记入"已见"集合（在本轮判定 hasFreshCalls <b>之后</b>调用）。 */
    public static void markSeen(List<String> roundSignatures, Set<String> seen) {
        for (String sig : roundSignatures) {
            if (sig != null) {
                seen.add(sig);
            }
        }
    }

    /** 构造调用签名：工具名 + 参数 JSON 原样拼接，作为"重复调用"判定键。 */
    public static String signature(String toolName, String argumentsJson) {
        String n = toolName == null ? "" : toolName.trim();
        String a = argumentsJson == null ? "{}" : argumentsJson.replaceAll("\\s+", "");
        return n + "|" + a;
    }

    /** 初始的"已见签名"集合（空）。provider 每轮把新签名 add 进来。 */
    public static Set<String> newSeen() {
        return new HashSet<>();
    }
}
