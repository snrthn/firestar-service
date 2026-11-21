package com.firestar.common;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

/**
 * Excel 列宽自适应工具（导入模板 / 错误回执 / 业务导出共用）。
 *
 * <p>为什么不用裸 {@code sheet.autoSizeColumn()}：POI 依赖 AWT 字体度量，
 * 服务器 headless 环境下中文常被按 1 字符估算，空内容列甚至会算到极窄，
 * 导出/下载下来表头和数据挤在一起（2026-11-22 虎哥截图报障）。</p>
 *
 * <p>这里补一层「最长内容显示宽度（中文/全角按 2 算）+ 右侧留白」的最小宽度兜底，
 * 只在 POI 算得更窄时抬起来，不覆盖 POI 算得更宽的情况。</p>
 */
public final class ExcelColumnWidth {

    /** POI 单列宽度上限（字符数）。 */
    private static final int MAX_WIDTH_CHARS = 60;

    /** 内容右侧留白字符数，避免刚好贴边。 */
    private static final int PADDING_CHARS = 4;

    /** 列宽单位：1 个字符 = 256。 */
    private static final int CHAR_UNIT = 256;

    private ExcelColumnWidth() {
    }

    /**
     * 按列内容撑开 [0, lastCol] 的列宽。
     *
     * @param sheet  工作表
     * @param lastCol 最后一列索引（含）
     */
    public static void autoSize(Sheet sheet, int lastCol) {
        for (int c = 0; c <= lastCol; c++) {
            sheet.autoSizeColumn(c);
            int maxDisplay = 0;
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                Cell cell = row.getCell(c);
                if (cell == null) continue;
                String v = cell.toString();
                if (v == null || v.isEmpty()) continue;
                maxDisplay = Math.max(maxDisplay, displayWidth(v));
            }
            int wanted = Math.min(maxDisplay + PADDING_CHARS, MAX_WIDTH_CHARS);
            int minWidth = wanted * CHAR_UNIT;
            if (sheet.getColumnWidth(c) < minWidth) sheet.setColumnWidth(c, minWidth);
        }
    }

    /** 显示宽度：中文/全角按 2 个西文字符宽算，ASCII 按 1。 */
    public static int displayWidth(String s) {
        if (s == null) return 0;
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += isWideChar(s.charAt(i)) ? 2 : 1;
        }
        return w;
    }

    /**
     * 宽字符判断（CJK / 全角 / 韩文），不依赖 JDK 19+ 的 Character.isWideFullWidth。
     * 项目编译目标为 Java 17，用 Unicode 区块范围比较，兼容 Java 8/11/17/21。
     */
    public static boolean isWideChar(char c) {
        return (c >= '\u1100' && c <= '\u115F')   // Hangul Jamo
                || (c >= '\u2E80' && c <= '\uA4CF')   // CJK 部首 + 统一表意文字（含扩展 A）
                || (c >= '\uAC00' && c <= '\uD7A3')   // Hangul 音节
                || (c >= '\uF900' && c <= '\uFAFF')   // CJK 兼容表意文字
                || (c >= '\uFE10' && c <= '\uFE19')   // 竖排标点
                || (c >= '\uFE30' && c <= '\uFE6F')   // CJK 兼容形式（全角标点）
                || (c >= '\uFF00' && c <= '\uFF60')   // 全角 ASCII 形式
                || (c >= '\uFFE0' && c <= '\uFFE6');  // 全角符号
    }
}
