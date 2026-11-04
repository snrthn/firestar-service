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
import java.util.List;

/**
 * 通用 Excel(.xlsx) 导出工具（任务2：业务数据页导出）。
 *
 * <p>设计：纯 POI，不依赖任何业务对象。调用方传入「数据行 List」+「列定义 List<Column>」，
 * 列定义声明 标题 与 对应 VO 的属性名（驼峰），工具内部用 hutool 反射取值，
 * 数值走 numeric cell、其余走文本，写出 xlsx 字节数组交给 Controller 以 ResponseEntity 下发。</p>
 *
 * <p>复用现有分页查询即可拿全量：如 {@code service.page(u, admin, 1, Integer.MAX_VALUE, 同筛选).getRecords()}，
 * 这样审计/字典翻译/权限过滤逻辑全部复用，无需为导出另写查询。</p>
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

    private ExcelExportUtil() {
    }

    public static byte[] export(List<?> data, List<Column> columns) throws IOException {
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("export");

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

            // 列宽自适应（中文按 2 倍估算）
            for (int i = 0; i < columns.size(); i++) {
                sheet.autoSizeColumn(i);
            }

            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void writeCell(Cell c, Object val) {
        if (val == null) {
            c.setBlank();
        } else if (val instanceof Number n) {
            c.setCellValue(n.doubleValue());
        } else if (val instanceof Boolean b) {
            c.setCellValue(b);
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
