package com.firestar.ai.spi;

/**
 * 工具声明（供应商无关；全项目唯一真源在 ToolRegistry）。
 * parametersJson 为 JSON Schema 字符串，描述入参；扣子侧注册插件时复用同一份。
 *
 * <p>requiredPerm：工具所需的权限码（对应 sys_permission.perm_code，与 Web @RequirePerm 同口径）。
 * <ul>
 *   <li>null：登录即可（公开只读工具，如客户/货物名）；</li>
 *   <li>非 null：非管理员必须持有 {@code PERM:<code>} 才能「看见 + 执行」该工具。</li>
 * </ul>
 * 双闸门：{@code ToolRegistry.allVisible(...)} 按权限裁剪下发给模型的工具清单（模型看不见就没法调），
 * {@code ToolExecutor} 执行前再校验一次（防扣子回调 / 篡改 tool_calls 绕过）。</p>
 */
public record ToolSpec(String name, String description, String parametersJson, String requiredPerm) {

    /** 兼容旧调用：无权限要求的公开工具 */
    public ToolSpec(String name, String description, String parametersJson) {
        this(name, description, parametersJson, null);
    }
}
