package com.firestar.handler;

import com.firestar.dto.ImportColumnDef;
import com.firestar.service.GoodsService;
import com.firestar.service.ImportHandler;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 货物档案导入 Handler（P0，方案 v3 §5）。
 *
 * <p>校验：名称必填 + 长度 + 批内去重 + status 枚举；同名 merge 空字段。
 * 走 GoodsService.upsert，与立项自动建档共用同一编号规则。</p>
 */
@Component
public class GoodsImportHandler implements ImportHandler {

    private static final String F_NAME = "goodsName";
    private static final String F_STATUS = "status";
    private static final String F_REMARK = "remark";

    private final GoodsService goodsService;

    public GoodsImportHandler(GoodsService goodsService) {
        this.goodsService = goodsService;
    }

    @Override
    public String key() {
        return "goods";
    }

    @Override
    public String displayName() {
        return "货物档案";
    }

    @Override
    public String sheetName() {
        return "货物";
    }

    @Override
    public List<ImportColumnDef> columns() {
        return List.of(
                ImportColumnDef.required("货物名称", F_NAME, "必填，最长 100 字；同名只补空字段", "壁挂式空调清洗"),
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
            int rowNo = i + 1;
            StringBuilder err = new StringBuilder();
            String name = val(row, F_NAME);
            if (name.isEmpty()) {
                err.append("货物名称必填；");
            } else if (name.length() > 100) {
                err.append("货物名称超过 100 字；");
            } else if (!seen.add(name)) {
                err.append("批内货物名称重复；");
            }
            String status = val(row, F_STATUS);
            if (!status.isEmpty() && !"ENABLED".equals(status) && !"DISABLED".equals(status)) {
                err.append("状态只能是 ENABLED/DISABLED；");
            }
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
            goodsService.upsert(val(row, F_NAME), fields, true, "历史数据导入 batch=" + batchNo);
            n++;
        }
        return n;
    }

    private static String val(Map<String, String> row, String field) {
        String v = row.get(field);
        return v == null ? "" : v.trim();
    }
}
