package com.firestar.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 导入模板列定义 —— 模板表头与解析列映射的唯一事实源（方案 v3 §2.2）。
 *
 * <p>同一个 {@code List<Column>} 既用于 POI 现场生成模板表头，又用于解析时
 * 按 title 定位列下标。模板与解析器永不漂移。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ImportColumnDef {

    /** 表头中文（模板显示 + 解析时按此匹配 Excel 列） */
    private String title;

    /** 字段标识（Handler 内部取值用，如 customerName） */
    private String field;

    /** 是否必填 */
    private boolean required;

    /** 字段说明（写入模板「填写说明」Sheet） */
    private String note;

    /** 示例值（写入模板数据 Sheet 的第 1 行示例） */
    private String example;

    /** 构造一个非必填列 */
    public static ImportColumnDef of(String title, String field, String note, String example) {
        return new ImportColumnDef(title, field, false, note, example);
    }

    /** 构造一个必填列 */
    public static ImportColumnDef required(String title, String field, String note, String example) {
        return new ImportColumnDef(title, field, true, note, example);
    }

    /** 取所有表头标题（顺序即列顺序） */
    public static List<String> titles(List<ImportColumnDef> defs) {
        List<String> out = new ArrayList<>(defs.size());
        for (ImportColumnDef d : defs) out.add(d.getTitle());
        return out;
    }
}
