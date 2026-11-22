package com.firestar.ai.spi;

/**
 * 工具执行端口（core 定义、biz 实现）。
 *
 * <p>OpenAI / Anthropic / DeepSeek 的 provider 位于 biz 模块，可直接注入 ToolExecutor；
 * CozeProvider 位于 core 模块（core 不能反向依赖 biz），故通过本接口解耦：
 * biz 的 ToolExecutor 实现本接口，ChatProviderManager 装配时把实现注入 CozeProvider。
 * 执行身份（username / isAdmin）与权限复核契约跟直连 provider 完全一致。</p>
 */
public interface ToolExecutionPort {

    /** 执行供应商发起的工具调用，返回已经 EgressMasker 脱敏的结果。 */
    ToolResult execute(ToolCall call, String username, boolean isAdmin);
}
