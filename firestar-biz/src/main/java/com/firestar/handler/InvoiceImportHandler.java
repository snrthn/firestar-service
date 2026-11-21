package com.firestar.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.dto.ImportColumnDef;
import com.firestar.entity.Invoice;
import com.firestar.entity.Project;
import com.firestar.entity.Receivable;
import com.firestar.mapper.InvoiceMapper;
import com.firestar.mapper.ProjectMapper;
import com.firestar.mapper.ReceivableMapper;
import com.firestar.service.ImportHandler;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * 期初发票导入 Handler（P1，方案 v3 §5）。
 *
 * <p>直接落 fin_invoice，approval_status=ISSUED（免审批、跳过 issue() 的职责分离校验），
 * applicant_id=clerk_id=导入人；税额按「金额 × 税率」重算，不信任模板传入；
 * apply_no 自生成 FP-日期-###，票号查重（含逻辑删除行）。购方六要素按模板写入快照。</p>
 */
@Component
public class InvoiceImportHandler implements ImportHandler {

    private static final String F_PROJECT = "projectName";
    private static final String F_RECEIVABLE = "receivableNo";
    private static final String F_INVOICE_NO = "invoiceNo";
    private static final String F_CODE = "invoiceCode";
    private static final String F_DATE = "invoiceDate";
    private static final String F_AMOUNT = "invoiceAmount";
    private static final String F_RATE = "taxRate";
    private static final String F_TYPE = "invoiceType";
    private static final String F_BUYER = "buyerName";
    private static final String F_BUYER_TAX = "buyerTaxNo";
    private static final String F_BUYER_ADDR = "buyerAddress";
    private static final String F_BUYER_PHONE = "buyerPhone";
    private static final String F_BUYER_BANK = "buyerBankName";
    private static final String F_BUYER_ACCOUNT = "buyerBankAccount";

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final InvoiceMapper invoiceMapper;
    private final ProjectMapper projectMapper;
    private final ReceivableMapper receivableMapper;

    public InvoiceImportHandler(InvoiceMapper invoiceMapper, ProjectMapper projectMapper,
                                ReceivableMapper receivableMapper) {
        this.invoiceMapper = invoiceMapper;
        this.projectMapper = projectMapper;
        this.receivableMapper = receivableMapper;
    }

    @Override
    public String key() {
        return "invoice_opening";
    }

    @Override
    public String displayName() {
        return "期初发票";
    }

    @Override
    public String sheetName() {
        return "发票";
    }

    @Override
    public List<ImportColumnDef> columns() {
        return List.of(
                ImportColumnDef.required("项目名称", F_PROJECT, "必填，须已存在", "2025年度设备采购"),
                ImportColumnDef.of("应收单号", F_RECEIVABLE, "选填，须已存在的应收单号", "YS-20250101-001"),
                ImportColumnDef.of("发票号码", F_INVOICE_NO, "税务票号，选填但不可重复", "04403260011112345678"),
                ImportColumnDef.of("发票代码", F_CODE, "选填", "044032600111"),
                ImportColumnDef.required("开票日期", F_DATE, "必填，格式 yyyy-MM-dd", "2025-03-01"),
                ImportColumnDef.required("发票金额", F_AMOUNT, "必填，数字且 >0", "50000.00"),
                ImportColumnDef.of("税率", F_RATE, "小数形式，如 0.13；留空 0", "0.13"),
                ImportColumnDef.of("发票类型", F_TYPE, "NORMAL / SPECIAL，留空默认 NORMAL", "NORMAL"),
                ImportColumnDef.of("购方名称", F_BUYER, "选填，购方六要素快照", "北京某某科技有限公司"),
                ImportColumnDef.of("购方税号", F_BUYER_TAX, "选填", "91110105MA01XXXXXX"),
                ImportColumnDef.of("购方地址", F_BUYER_ADDR, "选填", "北京市朝阳区xx路1号"),
                ImportColumnDef.of("购方电话", F_BUYER_PHONE, "选填", "010-12345678"),
                ImportColumnDef.of("购方开户行", F_BUYER_BANK, "选填", "工商银行xx支行"),
                ImportColumnDef.of("购方账号", F_BUYER_ACCOUNT, "选填", "1234567890123456")
        );
    }

    @Override
    public String notes() {
        return "1. 带 * 为必填项；2. 单次最多 2000 行；3. 项目须已存在，应收单号若填写须已存在；"
                + "4. 发票号码不可与历史重复；5. 税额按「金额 × 税率」自动重算（税率用小数，如 0.13）；"
                + "6. 期初发票直接置为已开票态、申请人=开票人=导入人（豁免职责分离）。";
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
            String rvNo = val(row, F_RECEIVABLE);
            if (!rvNo.isEmpty() && receivableMapper.selectCount(new LambdaQueryWrapper<Receivable>()
                    .eq(Receivable::getReceivableNo, rvNo)) == 0) {
                err.append("应收单号【" + rvNo + "】不存在；");
            }
            BigDecimal amount = parseNum(val(row, F_AMOUNT), err, "发票金额");
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) err.append("发票金额必须 >0；");
            String rate = val(row, F_RATE);
            if (!rate.isEmpty()) {
                BigDecimal r = parseNum(rate, err, "税率");
                if (r != null && (r.signum() < 0 || r.compareTo(BigDecimal.ONE) > 0)) err.append("税率须在 0~1 之间（小数形式）；");
            }
            String date = val(row, F_DATE);
            if (date.isEmpty()) {
                err.append("开票日期必填；");
            } else {
                try { LocalDate.parse(date, DF); } catch (DateTimeParseException e) { err.append("开票日期格式应为 yyyy-MM-dd；"); }
            }
            String type = val(row, F_TYPE);
            if (!type.isEmpty() && !"NORMAL".equals(type) && !"SPECIAL".equals(type)) {
                err.append("发票类型只能是 NORMAL/SPECIAL；");
            }
            String no = val(row, F_INVOICE_NO);
            if (!no.isEmpty()) {
                if (!nos.add(no)) {
                    err.append("批内发票号码重复；");
                } else if (invoiceMapper.selectIdByInvoiceNo(no, null) != null) {
                    err.append("发票号码已存在；");
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
            String rvNo = val(row, F_RECEIVABLE);
            Long receivableId = null;
            if (!rvNo.isEmpty()) {
                Receivable rv = receivableMapper.selectOne(new LambdaQueryWrapper<Receivable>()
                        .eq(Receivable::getReceivableNo, rvNo).last("LIMIT 1"));
                if (rv != null) receivableId = rv.getId();
            }
            BigDecimal amount = new BigDecimal(val(row, F_AMOUNT));
            BigDecimal rate = val(row, F_RATE).isEmpty() ? BigDecimal.ZERO : new BigDecimal(val(row, F_RATE));
            BigDecimal tax = amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);

            Invoice iv = new Invoice();
            iv.setProjectId(project.getId());
            iv.setReceivableId(receivableId == null ? 0L : receivableId);
            iv.setInvoiceNo(val(row, F_INVOICE_NO).isEmpty() ? null : val(row, F_INVOICE_NO));
            iv.setInvoiceCode(val(row, F_CODE));
            iv.setInvoiceAmount(amount);
            iv.setTaxRate(rate);
            iv.setTaxAmount(tax);
            iv.setInvoiceDate(LocalDate.parse(val(row, F_DATE), DF));
            String type = val(row, F_TYPE);
            iv.setInvoiceType(type.isEmpty() ? "NORMAL" : type);
            iv.setDocDirection("BLUE");
            iv.setApprovalStatus("ISSUED"); // 期初直接已开票
            iv.setApplicantId(operatorId);
            iv.setClerkId(operatorId);      // 豁免职责分离：申请人=开票人=导入人
            iv.setApplyNo(genApplyNo());
            // 购方六要素快照
            iv.setBuyerName(val(row, F_BUYER));
            iv.setBuyerTaxNo(val(row, F_BUYER_TAX));
            iv.setBuyerAddress(val(row, F_BUYER_ADDR));
            iv.setBuyerPhone(val(row, F_BUYER_PHONE));
            iv.setBuyerBankName(val(row, F_BUYER_BANK));
            iv.setBuyerBankAccount(val(row, F_BUYER_ACCOUNT));
            iv.setCreatedBy(operatorId);
            iv.setExt(UserImportHandler.importExt(batchNo));
            invoiceMapper.insert(iv);
            // 累加项目已开票冗余列
            project.setInvoicedAmount(nz(project.getInvoicedAmount()).add(amount));
            projectMapper.updateById(project);
            n++;
        }
        return n;
    }

    private String genApplyNo() {
        String date = LocalDateTime.now().format(NO_FMT);
        Integer maxSeq = invoiceMapper.selectMaxApplyDailySeq(date);
        return String.format("FP-%s-%03d", date, (maxSeq == null ? 0 : maxSeq) + 1);
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
