package com.firestar.ai.spi;

/**
 * 工具声明（供应商无关；全项目唯一真源在 ToolRegistry）。
 * parametersJson 为 JSON Schema 字符串，描述入参；扣子侧注册插件时复用同一份。
 */
public record ToolSpec(String name, String description, String parametersJson) {
}
