package com.firestar.common;

import cn.hutool.core.bean.BeanUtil;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;

/**
 * 通用 Excel(.xlsx) 导出工具（业务数据页导出）。
 *
 * <p>设计：纯 POI，不依赖任何业务对象。调用方传入「数据行 List」+「列定义 List<Column>」，
 * 列定义声明 标题 与 对应 VO 的属性名（驼峰），工具内部用 hutool 反射取值，
 * 数值走 numeric cell、时间走「与页面一致的格式化文本」、其余走文本，
 * 写出 xlsx 字节数组交给 Controller 以 ResponseEntity 下发。</p>
 *
 * <p>时间格式约定（2026-11-22 虎哥：导出时间必须与页面一致）：
 * LocalDateTime → {@code yyyy-MM-dd HH:mm:ss}、LocalDate → {@code yyyy-MM-dd}、
 * LocalTime → {@code HH:mm:ss}、java.util.Date → {@code yyyy-MM-dd HH:mm:ss}。
 * 与前端 {@code utils/datetime.ts} 的 formatDateTime / formatDate 完全对齐，
 * 避免出现 {@code 2026-11-01T01:31:42} 这种 ISO 原始串。</p>
 */
public final class ExcelExportUtil {

    /** 列定义：title=表头中文，field=VO 属性名（驼峰，对应 getter） */
    public static class Column {
        public final String title;
        public final String field;

        public Column(String title, String field) {
            this.title = title;
            this.field = field;
        }
    }

    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter FILE_STAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private ExcelExportUtil() {
    }

    public static byte[] export(List<?> data, List<Column> columns) throws IOException {
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("数据");

            // 表头
            CellStyle headerStyle = headerStyle(wb);
            Row header = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                Cell c = header.createCell(i);
                c.setCellValue(columns.get(i).title);
                c.setCellStyle(headerStyle);
            }

            // 数据行
            int rowIdx = 1;
            for (Object obj : data) {
                Row row = sheet.createRow(rowIdx++);
                for (int i = 0; i < columns.size(); i++) {
                    Column col = columns.get(i);
                    Object val = BeanUtil.getFieldValue(obj, col.field);
                    Cell c = row.createCell(i);
                    writeCell(c, val);
                }
            }

            // 列宽按内容撑开（中文双宽估算，与导入模板同一套逻辑）
            ExcelColumnWidth.autoSize(sheet, columns.size() - 1);

            wb.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 生成导出附件的 Content-Disposition 头：{@code 中文名_yyyyMMdd_HHmmss.xlsx}。
     *
     * <p>中文名走 RFC 5987 的 {@code filename*=UTF-8''...}，同时保留一个 ASCII 的
     * {@code filename=} 兜底（老浏览器 / 部分代理只认后者）。</p>
     *
     * @param baseName 中文业务名，如「收款管理」
     */
    public static String contentDisposition(String baseName) {
        String stamp = LocalDateTime.now().format(FILE_STAMP_FMT);
        String fileName = baseName + "_" + stamp + ".xlsx";
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        String asciiFallback = "export_" + stamp + ".xlsx";
        return "attachment; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + encoded;
    }

    private static void writeCell(Cell c, Object val) {
        if (val == null) {
            c.setBlank();
        } else if (val instanceof Number n) {
            c.setCellValue(n.doubleValue());
        } else if (val instanceof Boolean b) {
            c.setCellValue(b);
        } else if (val instanceof LocalDateTime dt) {
            c.setCellValue(dt.format(DATE_TIME_FMT));
        } else if (val instanceof LocalDate d) {
            c.setCellValue(d.format(DATE_FMT));
        } else if (val instanceof LocalTime t) {
            c.setCellValue(t.format(TIME_FMT));
        } else if (val instanceof Date d) {
            c.setCellValue(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d));
        } else {
            c.setCellValue(val.toString());
        }
    }

    private static CellStyle headerStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        return s;
    }
}
