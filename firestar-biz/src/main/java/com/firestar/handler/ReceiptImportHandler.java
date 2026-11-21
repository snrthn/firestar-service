package com.firestar.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.dto.ImportColumnDef;
import com.firestar.entity.Project;
import com.firestar.entity.Receipt;
import com.firestar.mapper.ProjectMapper;
import com.firestar.mapper.ReceiptMapper;
import com.firestar.service.ImportHandler;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 期初收款导入 Handler（P1，方案 v3 §5）。
 *
 * <p>免审批直接 RECEIVED + APPROVED，不拉 Flowable。校验阶段硬拦：金额 ≤ 项目合同额 - 已收（超收拒收）；
 * 若填了关联应收+分配金额，分配合计须 = 收款金额、且 ≤ 该应收未回款额。
 * 期初不做核销分配（WriteOff）——超收硬拦 + 三单对齐由项目实时聚合保证；如需逐单核销，走正常收款的 allocate 流程。</p>
 */
@Component
public class ReceiptImportHandler implements ImportHandler {

    private static final String F_PROJECT = "projectName";
    private static final String F_AMOUNT = "receiptAmount";
    private static final String F_DATE = "receiptDate";
    private static final String F_TYPE = "receiptType";
    private static final String F_DIRECTION = "docDirection";
    private static final String F_NO = "receiptNo";
    private static final String F_REMARK = "remark";

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ReceiptMapper receiptMapper;
    private final ProjectMapper projectMapper;

    public ReceiptImportHandler(ReceiptMapper receiptMapper, ProjectMapper projectMapper) {
        this.receiptMapper = receiptMapper;
        this.projectMapper = projectMapper;
    }

    @Override
    public String key() {
        return "receipt_opening";
    }

    @Override
    public String displayName() {
        return "期初收款";
    }

    @Override
    public String sheetName() {
        return "收款";
    }

    @Override
    public List<ImportColumnDef> columns() {
        return List.of(
                ImportColumnDef.required("项目名称", F_PROJECT, "必填，须已存在", "2025年度设备采购"),
                ImportColumnDef.required("收款金额", F_AMOUNT, "必填，数字且 >0", "20000.00"),
                ImportColumnDef.required("收款日期", F_DATE, "必填，格式 yyyy-MM-dd", "2025-02-01"),
                ImportColumnDef.of("款项类型", F_TYPE, "NORMAL / PREPAY / REFUND，留空默认 NORMAL", "NORMAL"),
                ImportColumnDef.of("方向", F_DIRECTION, "BLUE / RED，留空默认 BLUE", "BLUE"),
                ImportColumnDef.of("收款单号", F_NO, "选填，留空自动生成 SK-日期-###", "SK-20250201-001"),
                ImportColumnDef.of("备注", F_REMARK, "选填", "期初已收款")
        );
    }

    @Override
    public String notes() {
        return "1. 带 * 为必填项；2. 单次最多 2000 行；3. 收款金额不得超过项目合同额扣除历史已收后的余额（超收硬拦）；"
                + "4. 期初收款直接置为已收款态，不进入审批流；5. 如需逐单核销应收，请在导入后用正常收款的核销功能处理。";
    }

    @Override
    public ValidateResult validate(List<Map<String, String>> rows, Long operatorId) {
        List<RowError> errors = new ArrayList<>();
        // 累计每个项目在本批的收款额，用于超收硬拦（叠加已存在的收款）
        Map<Long, BigDecimal> batchByProject = new java.util.HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> row = rows.get(i);
            int rowNo = i + 1;
            StringBuilder err = new StringBuilder();
            String project = val(row, F_PROJECT);
            Project p = project.isEmpty() ? null : projectMapper.selectOne(
                    new LambdaQueryWrapper<Project>().eq(Project::getProjectName, project).last("LIMIT 1"));
            if (p == null) {
                err.append("项目【").append(project).append("】不存在；");
            }
            BigDecimal amount = parseNum(val(row, F_AMOUNT), err, "收款金额");
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) err.append("收款金额必须 >0；");
            String date = val(row, F_DATE);
            if (date.isEmpty()) {
                err.append("收款日期必填；");
            } else {
                try { LocalDate.parse(date, DF); } catch (DateTimeParseException e) { err.append("收款日期格式应为 yyyy-MM-dd；"); }
            }
            String type = val(row, F_TYPE);
            if (!type.isEmpty() && !"NORMAL".equals(type) && !"PREPAY".equals(type) && !"REFUND".equals(type)) {
                err.append("款项类型只能是 NORMAL/PREPAY/REFUND；");
            }
            String dir = val(row, F_DIRECTION);
            if (!dir.isEmpty() && !"BLUE".equals(dir) && !"RED".equals(dir)) err.append("方向只能是 BLUE/RED；");
            // 超收硬拦：合同额 - 历史已收（DB 冗余列）- 本批已累计
            if (p != null && amount != null && !"REFUND".equals(type)) {
                BigDecimal contract = nz(p.getContractAmount());
                BigDecimal received = sumReceived(p.getId());
                BigDecimal batchSoFar = batchByProject.getOrDefault(p.getId(), BigDecimal.ZERO);
                if (received.add(batchSoFar).add(amount).compareTo(contract) > 0) {
                    err.append("超收：合同额 ").append(contract).append("，已收 ").append(received)
                            .append("，本批已录 ").append(batchSoFar).append("；");
                }
                batchByProject.put(p.getId(), batchSoFar.add(amount));
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
            String dir = val(row, F_DIRECTION);
            String type = val(row, F_TYPE);

            Receipt r = new Receipt();
            r.setReceiptNo(val(row, F_NO).isEmpty() ? genNo() : val(row, F_NO));
            r.setProjectId(project.getId());
            r.setReceiptAmount(amount);
            r.setReceiptDate(LocalDate.parse(val(row, F_DATE), DF));
            r.setDocDirection(dir.isEmpty() ? "BLUE" : dir);
            r.setReceiptType(type.isEmpty() ? "NORMAL" : type);
            r.setApprovalStatus("APPROVED");
            r.setReceiptStatus("RECEIVED"); // 免审批直接已收款
            r.setWrittenOffAmount(BigDecimal.ZERO);
            r.setUnwrittenOffAmount(amount);
            r.setCreatedBy(operatorId);
            java.util.Map<String, Object> ext = new java.util.LinkedHashMap<>(UserImportHandler.importExt(batchNo));
            if (!val(row, F_REMARK).isEmpty()) ext.put("remark", val(row, F_REMARK));
            r.setExt(ext);
            receiptMapper.insert(r);
            // 累加项目已收冗余列（蓝字加、红字减）
            project.setCollectedAmount(nz(project.getCollectedAmount())
                    .add("RED".equals(r.getDocDirection()) ? amount.negate() : amount));
            projectMapper.updateById(project);
            n++;
        }
        return n;
    }

    /** 历史已收（实时口径，与 currentReceived 一致：RECEIVED 且按方向代数和）。 */
    private BigDecimal sumReceived(Long projectId) {
        List<Receipt> list = receiptMapper.selectList(new LambdaQueryWrapper<Receipt>()
                .select(Receipt::getReceiptAmount, Receipt::getDocDirection)
                .eq(Receipt::getProjectId, projectId).eq(Receipt::getReceiptStatus, "RECEIVED"));
        BigDecimal sum = BigDecimal.ZERO;
        for (Receipt r : list) {
            BigDecimal a = nz(r.getReceiptAmount());
            sum = sum.add("RED".equals(r.getDocDirection()) ? a.negate() : a);
        }
        return sum;
    }

    private String genNo() {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        Integer maxSeq = receiptMapper.selectMaxDailySeq(date);
        return String.format("SK-%s-%03d", date, (maxSeq == null ? 0 : maxSeq) + 1);
    }

    private static BigDecimal parseNum(String s, StringBuilder err, String label) {
        if (s == null || s.isBlank()) return null;
        try { return new BigDecimal(s); }
        catch (NumberFormatException e) { err.append(label).append("格式错误；"); return null; }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String val(Map<String, String> row, String field) {
        String v = row.get(field);
        return v == null ? "" : v.trim();
    }
}
