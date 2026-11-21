package com.firestar.common;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导出格式守卫（2026-11-22 虎哥：时间格式与页面一致、列宽按内容撑开、文件名中文+时间戳）。
 */
class ExcelExportUtilTest {

    /** 模拟导出 VO（hutool 反射走 getter） */
    public static class Row0 {
        private String name = "陈医生医";
        private Double amount = 2500d;
        private LocalDate receiptDate = LocalDate.of(2026, 11, 1);
        private LocalDateTime createdAt = LocalDateTime.of(2026, 11, 1, 1, 31, 42);

        public String getName() { return name; }
        public Double getAmount() { return amount; }
        public LocalDate getReceiptDate() { return receiptDate; }
        public LocalDateTime getCreatedAt() { return createdAt; }
    }

    private static final List<ExcelExportUtil.Column> COLS = List.of(
            new ExcelExportUtil.Column("客户名称", "name"),
            new ExcelExportUtil.Column("收款金额", "amount"),
            new ExcelExportUtil.Column("收款日期", "receiptDate"),
            new ExcelExportUtil.Column("创建时间", "createdAt"));

    @Test
    @DisplayName("1. 时间格式与页面统一：LocalDateTime 不带 T，LocalDate 只到天")
    void dateFormatMatchesPage() throws Exception {
        byte[] xlsx = ExcelExportUtil.export(List.of(new Row0()), COLS);
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Row row = wb.getSheetAt(0).getRow(1);
            assertEquals("陈医生医", row.getCell(0).toString());
            assertEquals("2026-11-01", row.getCell(2).toString(), "LocalDate 应输出 yyyy-MM-dd");
            assertEquals("2026-11-01 01:31:42", row.getCell(3).toString(), "LocalDateTime 应输出 yyyy-MM-dd HH:mm:ss（不得带 T）");
        }
    }

    @Test
    @DisplayName("2. 列宽按内容撑开：中文按双宽估算 + 留白，不挤压")
    void columnWidthFitsContent() throws Exception {
        byte[] xlsx = ExcelExportUtil.export(List.of(new Row0(), new Row0()), COLS);
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            // 「客户名称」表头 4 汉字 = 8 显示宽，+4 留白 = 12 字符
            assertTrue(sheet.getColumnWidth(0) >= 12 * 256,
                    "客户名称列宽应 >= 12 字符，实际 " + sheet.getColumnWidth(0) / 256.0);
            // 「创建时间」内容 19 字符，+4 留白 = 23 字符
            assertTrue(sheet.getColumnWidth(3) >= 23 * 256,
                    "创建时间列宽应 >= 23 字符，实际 " + sheet.getColumnWidth(3) / 256.0);
        }
    }

    @Test
    @DisplayName("3. 文件名头：中文业务名 + yyyyMMdd_HHmmss，走 RFC5987 编码")
    void contentDispositionChineseWithTimestamp() {
        String cd = ExcelExportUtil.contentDisposition("收款管理");
        assertTrue(cd.startsWith("attachment;"), "应为附件下载");
        assertTrue(cd.contains("filename*=UTF-8''"), "中文名需走 RFC5987");
        String encoded = cd.substring(cd.indexOf("filename*=UTF-8''") + "filename*=UTF-8''".length());
        String decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        assertTrue(decoded.matches("收款管理_\\d{8}_\\d{6}\\.xlsx"),
                "文件名应为 收款管理_yyyyMMdd_HHmmss.xlsx，实际 " + decoded);
    }
}
