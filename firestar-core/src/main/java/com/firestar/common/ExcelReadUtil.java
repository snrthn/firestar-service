package com.firestar.common;

import com.firestar.dto.ImportColumnDef;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 导入 Excel 解析工具（纯 POI，与 ExcelExportUtil 对称）。
 *
 * <p>【表头映射】按 {@code ImportColumnDef.title} 在第一行定位列下标，
 * 而不是写死列顺序——用户调整列顺序也能正确解析。缺失必填表头直接报错。
 * 【修正批次识别】错误 xlsx 有两个隐藏列 {@code _batch_no}/{@code _row_no}，
 * 解析时一并读出供 ImportService 判断 parentBatchNo。</p>
 */
public final class ExcelReadUtil {

    /** 单次导入硬上限（方案 v3） */
    public static final int MAX_ROWS = 2000;

    private static final String HIDDEN_BATCH = "_batch_no";
    private static final String HIDDEN_ROW = "_row_no";

    private ExcelReadUtil() {
    }

    /** 解析结果 */
    public static class Parsed {
        /** 每行 field→原始字符串 */
        private final List<Map<String, String>> rows = new ArrayList<>();
        /** 每行对应的 Excel 物理行号（含表头，从 2 开始） */
        private final List<Integer> rowNumbers = new ArrayList<>();
        /** 修正批次来源批次号（无隐藏列则 null） */
        private String parentBatchNo;

        public List<Map<String, String>> getRows() {
            return rows;
        }

        public List<Integer> getRowNumbers() {
            return rowNumbers;
        }

        public String getParentBatchNo() {
            return parentBatchNo;
        }
    }

    /**
     * 解析 xlsx 第一个 Sheet。
     *
     * @param bytes   文件字节
     * @param columns 列定义（来自 Handler.columns()）
     * @return 解析结果
     * @throws IOException xlsx 损坏 / 非 xlsx
     * @throws IllegalArgumentException 表头缺失 / 超 2000 行
     */
    public static Parsed parse(byte[] bytes, List<ImportColumnDef> columns) throws IOException {
        Parsed result = new Parsed();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null) {
                throw new IllegalArgumentException("Excel 文件没有工作表");
            }
            DataFormatter fmt = new DataFormatter();
            Row header = sheet.getRow(0);
            if (header == null) {
                throw new IllegalArgumentException("Excel 缺少表头行");
            }

            // title → 列下标
            Map<String, Integer> titleToIdx = new LinkedHashMap<>();
            for (int i = 0; i < header.getLastCellNum(); i++) {
                String t = normalizeTitle(cellText(header.getCell(i), fmt));
                if (!t.isEmpty()) {
                    titleToIdx.putIfAbsent(t, i);
                }
            }
            // 校验列定义的表头都存在
            for (ImportColumnDef col : columns) {
                if (!titleToIdx.containsKey(normalizeTitle(col.getTitle()))) {
                    throw new IllegalArgumentException("Excel 缺少必需表头：「" + col.getTitle() + "」");
                }
            }
            Integer batchIdx = titleToIdx.get(HIDDEN_BATCH);
            Integer rowIdx = titleToIdx.get(HIDDEN_ROW);

            int last = sheet.getLastRowNum();
            int dataCount = last; // 数据行数（去掉表头行 0）
            if (dataCount > MAX_ROWS) {
                throw new IllegalArgumentException("单次最多 " + MAX_ROWS + " 行，实际 " + dataCount
                        + " 行，请拆分为多个文件后重新提交");
            }

            for (int r = 1; r <= last; r++) {
                Row row = sheet.getRow(r);
                if (row == null || isBlank(row, fmt)) {
                    continue; // 跳过全空行
                }
                Map<String, String> record = new HashMap<>();
                for (ImportColumnDef col : columns) {
                    Integer idx = titleToIdx.get(normalizeTitle(col.getTitle()));
                    record.put(col.getField(), idx == null ? "" : cellText(row.getCell(idx), fmt).trim());
                }
                // 隐藏列：只取首行的 parentBatchNo（同一修正文件所有行都应相同）
                if (result.parentBatchNo == null && batchIdx != null) {
                    String b = cellText(row.getCell(batchIdx), fmt).trim();
                    if (!b.isEmpty()) result.parentBatchNo = b;
                }
                // 隐藏行号：用于定位错误行对应的原始物理行（修正文件里保留）
                String originalRow = rowIdx == null ? null : cellText(row.getCell(rowIdx), fmt).trim();
                int physicalRow = originalRow != null && originalRow.matches("\\d+")
                        ? Integer.parseInt(originalRow) : (r + 1);
                result.rows.add(record);
                result.rowNumbers.add(physicalRow);
            }
        }
        return result;
    }

    /**
     * 表头归一化：模板对必填列渲染为「标题 *」，解析时去掉星号标记再匹配，
     * 与 ExcelTemplateUtil.findHeaderIndex 的规则保持一致。
     * 用户手工去掉星号的文件同样能识别。
     */
    private static String normalizeTitle(String t) {
        if (t == null) return "";
        return t.replace(" *", "").replace("＊", "").trim();
    }

    /** 单元格文本（数值/日期/布尔统一走 DataFormatter 保真） */
    private static String cellText(Cell c, DataFormatter fmt) {
        if (c == null) return "";
        if (c.getCellType() == CellType.FORMULA) {
            return fmt.formatCellValue(c);
        }
        return fmt.formatCellValue(c);
    }

    /** 整行是否为空 */
    private static boolean isBlank(Row row, DataFormatter fmt) {
        for (int i = 0; i < row.getLastCellNum(); i++) {
            if (!cellText(row.getCell(i), fmt).trim().isEmpty()) return false;
        }
        return true;
    }
}
