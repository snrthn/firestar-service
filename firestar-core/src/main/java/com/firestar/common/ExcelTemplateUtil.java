package com.firestar.common;

import com.firestar.dto.ImportColumnDef;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 导入模板 / 错误回执 xlsx 生成工具（方案 v3 §2.2 / §4.1）。
 *
 * <p>模板不落库不落盘，随请求现场生成；表头直接取 Handler.columns() 的 title，
 * 与 ExcelReadUtil 共用同一份列定义，杜绝模板与解析器漂移。</p>
 */
public final class ExcelTemplateUtil {

    private static final String ERROR_COL = "错误信息";
    private static final String HIDDEN_BATCH = "_batch_no";
    private static final String HIDDEN_ROW = "_row_no";

    private ExcelTemplateUtil() {
    }

    /** 生成空模板：Sheet1 数据表头 + 1 行示例；Sheet2 填写说明 */
    public static byte[] template(String sheetName, List<ImportColumnDef> columns, String notes) throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet data = wb.createSheet(sheetName);
            CellStyle header = headerStyle(wb, true);
            Row h = data.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                ImportColumnDef col = columns.get(i);
                Cell c = h.createCell(i);
                c.setCellValue(col.getTitle() + (col.isRequired() ? " *" : ""));
                c.setCellStyle(header);
            }
            // 示例行：只放 example（去掉 * 的纯值）
            Row sample = data.createRow(1);
            for (int i = 0; i < columns.size(); i++) {
                String ex = columns.get(i).getExample();
                if (ex != null && !ex.isEmpty()) sample.createCell(i).setCellValue(ex);
            }
            // 按内容撑开：autoSizeColumn 对中文按单宽算、空示例列会偏窄，补一层最小宽度兜底
            ExcelColumnWidth.autoSize(data, columns.size() - 1);

            writeNotesSheet(wb, columns, notes, false);
            wb.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 从原始文件还原「全部数据行」+ 错误信息列 + 隐藏列，生成可修改重传的错误 xlsx。
     *
     * @param originalBytes 原始 xlsx 字节（从 FileStorage 读取）
     * @param columns       列定义
     * @param errors        行号 → 错误信息（物理行号，含表头从 2 开始）
     * @param batchNo       原批次号（写入隐藏列 _batch_no）
     */
    public static byte[] errorBook(byte[] originalBytes, List<ImportColumnDef> columns,
                                   Map<Integer, String> errors, String batchNo) throws IOException {
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(originalBytes));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null) throw new IllegalArgumentException("原始文件无工作表");

            int maxCol = 0;
            for (ImportColumnDef c : columns) {
                Integer idx = findHeaderIndex(sheet, c.getTitle());
                if (idx != null) maxCol = Math.max(maxCol, idx);
            }
            int errCol = maxCol + 1;
            int batchCol = errCol + 1;
            int rowCol = errCol + 2;

            CellStyle errStyle = headerStyle(wb, true);
            Cell h = sheet.getRow(0).createCell(errCol);
            h.setCellValue(ERROR_COL);
            h.setCellStyle(errStyle);
            sheet.getRow(0).createCell(batchCol).setCellValue(HIDDEN_BATCH);
            sheet.getRow(0).createCell(rowCol).setCellValue(HIDDEN_ROW);

            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                int physicalRow = r + 1;
                String err = errors.get(physicalRow);
                if (err != null) row.createCell(errCol).setCellValue(err);
                row.createCell(batchCol).setCellValue(batchNo);
                row.createCell(rowCol).setCellValue(physicalRow);
            }
            // 隐藏列（列索引从 0 开始）
            sheet.setColumnHidden(batchCol, true);
            sheet.setColumnHidden(rowCol, true);
            ExcelColumnWidth.autoSize(sheet, errCol);

            if (wb.getSheet("填写说明") == null) {
                writeNotesSheet(wb, columns, "修正说明：改完错误行后原样重新上传，系统自动识别为修正批次。", true);
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static Integer findHeaderIndex(Sheet sheet, String title) {
        Row h = sheet.getRow(0);
        if (h == null) return null;
        for (int i = 0; i < h.getLastCellNum(); i++) {
            Cell c = h.getCell(i);
            if (c != null && c.toString().replace(" *", "").trim().equals(title.trim())) return i;
        }
        return null;
    }

    private static void writeNotesSheet(Workbook wb, List<ImportColumnDef> columns, String notes, boolean correction) {
        Sheet sheet = wb.createSheet("填写说明");
        int r = 0;
        CellStyle bold = headerStyle(wb, false);
        Cell titleCell = sheet.createRow(r++).createCell(0);
        titleCell.setCellValue(correction ? "【修正重传说明】" : "【填写说明】");
        titleCell.setCellStyle(bold);
        for (String line : notes.split("\\n")) {
            sheet.createRow(r++).createCell(0).setCellValue(line);
        }
        r++;
        Cell c0 = sheet.createRow(r++).createCell(0);
        c0.setCellValue("列");
        c0.setCellStyle(bold);
        sheet.getRow(r - 1).createCell(1).setCellValue("是否必填");
        sheet.getRow(r - 1).createCell(2).setCellValue("说明");
        for (ImportColumnDef col : columns) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(col.getTitle());
            row.createCell(1).setCellValue(col.isRequired() ? "必填" : "选填");
            row.createCell(2).setCellValue(col.getNote() == null ? "" : col.getNote());
        }
        sheet.setColumnWidth(0, 6000);
        sheet.setColumnWidth(2, 12000);
    }

    private static CellStyle headerStyle(Workbook wb, boolean required) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        return s;
    }
}
