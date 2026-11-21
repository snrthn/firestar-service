package com.firestar.service;

import com.firestar.dto.ImportColumnDef;

import java.util.List;
import java.util.Map;

/**
 * 导入处理器 SPI —— core 定义接口，biz 实现具体 9 类业务导入（方案 v3）。
 *
 * <p>每个 Handler 无状态（Spring 单例），一次导入分三步：
 * <ol>
 *   <li>{@link #columns()} / {@link #sheetName()}：模板与解析列映射的单一事实源；</li>
 *   <li>{@link #validate(List, Long)}：全量校验（纯内存 + 批量查库），返回每个错误行；
 *       校验阶段<b>不得写库</b>，有任何错误则整批不写；</li>
 *   <li>{@link #commit(List, Long, String)}：校验全通过后整批写入，返回成功行数；
 *       已在调用方的事务中，抛异常即整批回滚。</li>
 * </ol>
 *
 * <p>期初类 Handler 另需实现 {@link #needsReconcile()} 与 {@link #reconcile(String)} 做三单对齐门禁。</p>
 */
public interface ImportHandler {

    /** Handler key（与 import_key / 权限码 import:{key} 对应） */
    String key();

    /** 导入名称（通知 / 前端展示，如「客户档案」） */
    String displayName();

    /** 模板数据 Sheet 名（如「客户」） */
    default String sheetName() {
        return "数据";
    }

    /** 模板列定义（表头 + 字段 + 必填 + 说明 + 示例） */
    List<ImportColumnDef> columns();

    /** 模板顶部提示语（写入「填写说明」Sheet 第一行） */
    default String notes() {
        return "1. 带 * 为必填项；2. 单次最多 2000 行；3. 请勿修改表头名称与顺序。";
    }

    /** 期初类返回 true，触发写入后的三单对齐门禁 */
    default boolean needsReconcile() {
        return false;
    }

    /**
     * 全量校验（不写库）。
     *
     * @param rows       解析后的数据行，每行 field→原始字符串；key 为 columns() 的 field
     * @param operatorId 提交人 ID（用于归属校验，如修正批次归属）
     * @return 校验结果（errors 为空表示全部通过）
     */
    ValidateResult validate(List<Map<String, String>> rows, Long operatorId);

    /**
     * 整批写入（调用方已开启事务；本方法抛异常 → 整批回滚）。
     *
     * @param rows       已通过校验的数据行
     * @param operatorId 提交人 ID（期初单据 applicantId=导入人）
     * @param batchNo    批次号（写入 ext.import_batch_no 溯源）
     * @return 实际成功写入行数
     */
    int commit(List<Map<String, String>> rows, Long operatorId, String batchNo);

    /**
     * 三单对齐门禁（仅 needsReconcile()=true 时调用）。
     *
     * @param batchNo 批次号
     * @return 对账结果（ok=false → 批次置 PARTIAL）
     */
    default ReconcileResult reconcile(String batchNo) {
        return ReconcileResult.ok();
    }

    /** 校验结果 */
    class ValidateResult {
        private final List<RowError> errors;

        private ValidateResult(List<RowError> errors) {
            this.errors = errors;
        }

        public static ValidateResult pass() {
            return new ValidateResult(List.of());
        }

        public static ValidateResult fail(List<RowError> errors) {
            return new ValidateResult(errors);
        }

        public boolean ok() {
            return errors == null || errors.isEmpty();
        }

        public List<RowError> getErrors() {
            return errors;
        }
    }

    /** 单行错误（行号为 Excel 物理行号，含表头从 2 开始） */
    class RowError {
        private final int rowNo;
        private final String message;

        public RowError(int rowNo, String message) {
            this.rowNo = rowNo;
            this.message = message;
        }

        public int getRowNo() {
            return rowNo;
        }

        public String getMessage() {
            return message;
        }
    }

    /** 对账结果 */
    class ReconcileResult {
        private final boolean ok;
        private final String summary;

        private ReconcileResult(boolean ok, String summary) {
            this.ok = ok;
            this.summary = summary;
        }

        public static ReconcileResult ok() {
            return new ReconcileResult(true, null);
        }

        public static ReconcileResult diff(String summary) {
            return new ReconcileResult(false, summary);
        }

        public boolean isOk() {
            return ok;
        }

        public String getSummary() {
            return summary;
        }
    }
}
