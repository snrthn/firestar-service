package com.firestar.handler;

import com.firestar.dto.ImportColumnDef;
import com.firestar.service.CustomerService;
import com.firestar.service.ImportHandler;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 客户档案导入 Handler（P0，方案 v3 §5）。
 *
 * <p>校验：名称必填 + 长度 + 批内去重 + status 枚举；同名 merge 空字段（不覆盖已有值）。
 * 走 CustomerService.upsert，与立项自动建档共用同一编号规则。</p>
 */
@Component
public class CustomerImportHandler implements ImportHandler {

    private static final String F_NAME = "customerName";
    private static final String F_CONTACT = "contactPerson";
    private static final String F_PHONE = "contactPhone";
    private static final String F_ADDR = "address";
    private static final String F_TAX = "taxNo";
    private static final String F_BANK = "bankName";
    private static final String F_ACCOUNT = "bankAccount";
    private static final String F_BUYER_PHONE = "buyerPhone";
    private static final String F_STATUS = "status";
    private static final String F_REMARK = "remark";

    private final CustomerService customerService;

    public CustomerImportHandler(CustomerService customerService) {
        this.customerService = customerService;
    }

    @Override
    public String key() {
        return "customer";
    }

    @Override
    public String displayName() {
        return "客户档案";
    }

    @Override
    public String sheetName() {
        return "客户";
    }

    @Override
    public List<ImportColumnDef> columns() {
        return List.of(
                ImportColumnDef.required("客户名称", F_NAME, "必填，最长 100 字；同名只补空字段", "北京某某科技有限公司"),
                ImportColumnDef.of("联系人", F_CONTACT, "客户联系人", "张三"),
                ImportColumnDef.of("联系电话", F_PHONE, "手机号或座机", "13800138000"),
                ImportColumnDef.of("地址", F_ADDR, "客户地址", "北京市朝阳区xx路1号"),
                ImportColumnDef.of("税号", F_TAX, "统一社会信用代码", "91110105MA01XXXXXX"),
                ImportColumnDef.of("开户行", F_BANK, "开户银行", "工商银行xx支行"),
                ImportColumnDef.of("银行账号", F_ACCOUNT, "对公账号", "1234567890123456"),
                ImportColumnDef.of("购方电话", F_BUYER_PHONE, "发票购方电话", "010-12345678"),
                ImportColumnDef.of("状态", F_STATUS, "ENABLED / DISABLED，留空默认 ENABLED", "ENABLED"),
                ImportColumnDef.of("备注", F_REMARK, "补充说明", "历史数据导入")
        );
    }

    @Override
    public ValidateResult validate(List<Map<String, String>> rows, Long operatorId) {
        List<RowError> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> row = rows.get(i);
            int rowNo = i + 1; // 逻辑行号（ImportService 会映射回物理行）
            StringBuilder err = new StringBuilder();
            String name = val(row, F_NAME);
            if (name.isEmpty()) {
                err.append("客户名称必填；");
            } else if (name.length() > 100) {
                err.append("客户名称超过 100 字；");
            } else if (!seen.add(name)) {
                err.append("批内客户名称重复；");
            }
            String status = val(row, F_STATUS);
            if (!status.isEmpty() && !"ENABLED".equals(status) && !"DISABLED".equals(status)) {
                err.append("状态只能是 ENABLED/DISABLED；");
            }
            if (val(row, F_PHONE).length() > 40) err.append("联系电话过长；");
            if (val(row, F_TAX).length() > 64) err.append("税号过长；");
            if (err.length() > 0) errors.add(new RowError(rowNo, err.toString()));
        }
        return errors.isEmpty() ? ValidateResult.pass() : ValidateResult.fail(errors);
    }

    @Override
    public int commit(List<Map<String, String>> rows, Long operatorId, String batchNo) {
        int n = 0;
        for (Map<String, String> row : rows) {
            Map<String, String> fields = new TreeMap<>();
            for (ImportColumnDef col : columns()) {
                if (!F_NAME.equals(col.getField())) fields.put(col.getField(), val(row, col.getField()));
            }
            customerService.upsert(val(row, F_NAME), fields, true,
                    "历史数据导入 batch=" + batchNo);
            n++;
        }
        return n;
    }

    private static String val(Map<String, String> row, String field) {
        String v = row.get(field);
        return v == null ? "" : v.trim();
    }
}
