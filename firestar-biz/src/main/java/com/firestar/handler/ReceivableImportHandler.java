package com.firestar.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.dto.ImportColumnDef;
import com.firestar.entity.Project;
import com.firestar.entity.Receivable;
import com.firestar.mapper.ProjectMapper;
import com.firestar.mapper.ReceivableMapper;
import com.firestar.service.ImportHandler;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 期初应收导入 Handler（P0，方案 v3 §5）—— 最特殊的通道。
 *
 * <p>期初应收无对应发货单，shipment_id 必须留 NULL 以避开 uk_receivable_shipment 唯一键
 * （1 发货 = 1 应收）；source_type=OPENING_IMPORT 标识来源。六个金额列按「金额/已核销/已开票/已回款」
 * 初始化：unwrittenOff = 金额 - 已核销，uninvoiced = 金额 - 已开票，unreceived = 金额 - 已回款。
 * 校验拦住：项目存在、金额>0、三个"已"≤金额且 ≥0、应收单号批内/库内唯一。</p>
 */
@Component
public class ReceivableImportHandler implements ImportHandler {

    private static final String F_PROJECT = "projectName";
    private static final String F_NO = "receivableNo";
    private static final String F_AMOUNT = "receivableAmount";
    private static final String F_DATE = "receivableDate";
    private static final String F_DIRECTION = "docDirection";
    private static final String F_WRITTEN = "writtenOffAmount";
    private static final String F_INVOICED = "invoicedAmount";
    private static final String F_RECEIVED = "receivedAmount";
    private static final String F_REMARK = "remark";

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ReceivableMapper receivableMapper;
    private final ProjectMapper projectMapper;

    public ReceivableImportHandler(ReceivableMapper receivableMapper, ProjectMapper projectMapper) {
        this.receivableMapper = receivableMapper;
        this.projectMapper = projectMapper;
    }

    @Override
    public String key() {
        return "receivable_opening";
    }

    @Override
    public String displayName() {
        return "期初应收";
    }

    @Override
    public String sheetName() {
        return "应收";
    }

    @Override
    public List<ImportColumnDef> columns() {
        return List.of(
                ImportColumnDef.required("项目名称", F_PROJECT, "必填，须已存在", "2025年度设备采购"),
                ImportColumnDef.of("应收单号", F_NO, "选填，留空自动生成 YS-日期-###", "YS-20250101-001"),
                ImportColumnDef.required("应收金额", F_AMOUNT, "必填，数字且 >0", "50000.00"),
                ImportColumnDef.required("应收日期", F_DATE, "必填，格式 yyyy-MM-dd", "2025-01-15"),
                ImportColumnDef.of("方向", F_DIRECTION, "BLUE / RED，留空默认 BLUE", "BLUE"),
                ImportColumnDef.of("已核销金额", F_WRITTEN, "≤ 应收金额，留空 0", "0.00"),
                ImportColumnDef.of("已开票金额", F_INVOICED, "≤ 应收金额，留空 0", "0.00"),
                ImportColumnDef.of("已回款金额", F_RECEIVED, "≤ 应收金额，留空 0", "0.00"),
                ImportColumnDef.of("说明", F_REMARK, "选填", "期初未结应收")
        );
    }

    @Override
    public String notes() {
        return "1. 带 * 为必填项；2. 单次最多 2000 行；3. 项目须已存在（先导期初项目）；"
                + "4. 期初应收无发货单，shipment_id 留空，来源标记为 OPENING_IMPORT；"
                + "5. 三个「已」金额（已核销/已开票/已回款）均不得超过应收金额、不得为负。";
    }

    @Override
    public boolean needsReconcile() {
        return true;
    }

    @Override
    public ValidateResult validate(List<Map<String, String>> rows, Long operatorId) {
        List<RowError> errors = new ArrayList<>();
        Set<String> nos = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> row = rows.get(i);
            int rowNo = i + 1;
            StringBuilder err = new StringBuilder();
            String project = val(row, F_PROJECT);
            if (project.isEmpty()) {
                err.append("项目名称必填；");
            } else if (projectMapper.selectCount(new LambdaQueryWrapper<Project>()
                    .eq(Project::getProjectName, project)) == 0) {
                err.append("项目【" + project + "】不存在；");
            }
            BigDecimal amount = parseNum(val(row, F_AMOUNT), err, "应收金额");
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) err.append("应收金额必须 >0；");
            String date = val(row, F_DATE);
            if (date.isEmpty()) {
                err.append("应收日期必填；");
            } else {
                try { LocalDate.parse(date, DF); } catch (DateTimeParseException e) { err.append("应收日期格式应为 yyyy-MM-dd；"); }
            }
            String dir = val(row, F_DIRECTION);
            if (!dir.isEmpty() && !"BLUE".equals(dir) && !"RED".equals(dir)) err.append("方向只能是 BLUE/RED；");
            if (amount != null) {
                BigDecimal written = parseNum(val(row, F_WRITTEN), err, "已核销金额");
                BigDecimal invoiced = parseNum(val(row, F_INVOICED), err, "已开票金额");
                BigDecimal received = parseNum(val(row, F_RECEIVED), err, "已回款金额");
                if (written != null && (written.signum() < 0 || written.compareTo(amount) > 0)) err.append("已核销金额须在 0~应收金额之间；");
                if (invoiced != null && (invoiced.signum() < 0 || invoiced.compareTo(amount) > 0)) err.append("已开票金额须在 0~应收金额之间；");
                if (received != null && (received.signum() < 0 || received.compareTo(amount) > 0)) err.append("已回款金额须在 0~应收金额之间；");
            }
            String no = val(row, F_NO);
            if (!no.isEmpty()) {
                if (!nos.add(no)) {
                    err.append("批内应收单号重复；");
                } else if (receivableMapper.selectCount(new LambdaQueryWrapper<Receivable>()
                        .eq(Receivable::getReceivableNo, no)) > 0) {
                    err.append("应收单号已存在；");
                }
            }
            if (err.length() > 0) errors.add(new RowError(rowNo, err.toString()));
        }
        return errors.isEmpty() ? ValidateResult.pass() : ValidateResult.fail(errors);
    }

    @Override
    public int commit(List<Map<String, String>> rows, Long operatorId, String batchNo) {
        int n = 0;
        for (Map<String, String> row : rows) {
            Project project = projectMapper.selectOne(new LambdaQueryWrapper<Project>()
                    .eq(Project::getProjectName, val(row, F_PROJECT)).last("LIMIT 1"));
            BigDecimal amount = new BigDecimal(val(row, F_AMOUNT));
            BigDecimal written = nz(parseNumOrZero(val(row, F_WRITTEN)));
            BigDecimal invoiced = nz(parseNumOrZero(val(row, F_INVOICED)));
            BigDecimal received = nz(parseNumOrZero(val(row, F_RECEIVED)));

            Receivable r = new Receivable();
            r.setReceivableNo(val(row, F_NO).isEmpty() ? genNo() : val(row, F_NO));
            r.setProjectId(project.getId());
            r.setShipmentId(null); // 期初无发货单，避开 uk_receivable_shipment
            String dir = val(row, F_DIRECTION);
            r.setDocDirection(dir.isEmpty() ? "BLUE" : dir);
            r.setSourceType("OPENING_IMPORT");
            r.setReceivableAmount(amount);
            r.setWrittenOffAmount(written);
            r.setUnwrittenOffAmount(amount.subtract(written));
            r.setInvoicedAmount(invoiced);
            r.setUninvoicedAmount(amount.subtract(invoiced));
            r.setReceivedAmount(received);
            r.setUnreceivedAmount(amount.subtract(received));
            r.setReceivableDate(LocalDate.parse(val(row, F_DATE), DF));
            r.setStatus("CONFIRMED");
            r.setApplicantId(operatorId);
            r.setCreatedBy(operatorId);
            java.util.Map<String, Object> ext = new java.util.LinkedHashMap<>(UserImportHandler.importExt(batchNo));
            if (!val(row, F_REMARK).isEmpty()) ext.put("remark", val(row, F_REMARK));
            r.setExt(ext);
            receivableMapper.insert(r);
            // 累加项目应收冗余列（展示值走 amountsOf 实时聚合，此列仅参考）
            project.setReceivableAmount(nz(project.getReceivableAmount()).add(amount));
            projectMapper.updateById(project);
            n++;
        }
        return n;
    }

    private String genNo() {
        String date = LocalDateTime.now().format(NO_FMT);
        Long count = receivableMapper.selectCount(new LambdaQueryWrapper<Receivable>()
                .likeRight(Receivable::getReceivableNo, "YS-" + date + "-"));
        return String.format("YS-%s-%03d", date, (count == null ? 0 : count) + 1);
    }

    private static BigDecimal parseNum(String s, StringBuilder err, String label) {
        if (s == null || s.isBlank()) return null;
        try { return new BigDecimal(s); }
        catch (NumberFormatException e) { err.append(label).append("格式错误；"); return null; }
    }

    private static BigDecimal parseNumOrZero(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(s); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String val(Map<String, String> row, String field) {
        String v = row.get(field);
        return v == null ? "" : v.trim();
    }
}
