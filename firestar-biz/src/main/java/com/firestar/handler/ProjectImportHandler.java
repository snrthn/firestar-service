package com.firestar.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.dto.ImportColumnDef;
import com.firestar.entity.Customer;
import com.firestar.entity.Project;
import com.firestar.entity.User;
import com.firestar.mapper.CustomerMapper;
import com.firestar.mapper.ProjectMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.service.ImportHandler;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 期初项目导入 Handler（P0，方案 v3 §5）。
 *
 * <p>直接落 fin_project 主表，approval_status=APPROVED、合同状态由模板指定，不拉 Flowable。
 * 依赖前置：客户档案、用户（销售经理/签约人）须已存在。三个累计列初始为 0，
 * 由后续期初应收/收款/发票 Handler 累加；项目详情展示值走 amountsOf 实时聚合。</p>
 */
@Component
public class ProjectImportHandler implements ImportHandler {

    private static final String F_NAME = "projectName";
    private static final String F_CUSTOMER = "customerName";
    private static final String F_MANAGER = "salesManagerUsername";
    private static final String F_CONTRACT_STATUS = "contractStatus";
    private static final String F_CONTRACT_AMOUNT = "contractAmount";
    private static final String F_SIGNED_AT = "contractSignedAt";
    private static final String F_SIGNED_BY = "contractSignedByUsername";
    private static final String F_INVOICE_BEFORE = "allowInvoiceBeforeReceipt";
    private static final String F_REMARK = "remark";

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ProjectMapper projectMapper;
    private final CustomerMapper customerMapper;
    private final UserMapper userMapper;

    public ProjectImportHandler(ProjectMapper projectMapper, CustomerMapper customerMapper, UserMapper userMapper) {
        this.projectMapper = projectMapper;
        this.customerMapper = customerMapper;
        this.userMapper = userMapper;
    }

    @Override
    public String key() {
        return "project_opening";
    }

    @Override
    public String displayName() {
        return "期初项目";
    }

    @Override
    public String sheetName() {
        return "项目";
    }

    @Override
    public List<ImportColumnDef> columns() {
        return List.of(
                ImportColumnDef.required("项目名称", F_NAME, "必填，批内不可重复", "2025年度设备采购"),
                ImportColumnDef.required("客户名称", F_CUSTOMER, "必填，须已存在于客户档案", "北京某某科技有限公司"),
                ImportColumnDef.required("销售经理用户名", F_MANAGER, "必填，须已存在的用户名", "sales01"),
                ImportColumnDef.of("合同状态", F_CONTRACT_STATUS, "UNSIGNED / SIGNED，留空默认 SIGNED", "SIGNED"),
                ImportColumnDef.required("合同金额", F_CONTRACT_AMOUNT, "必填，数字且 ≥0", "100000.00"),
                ImportColumnDef.of("签约日期", F_SIGNED_AT, "格式 yyyy-MM-dd HH:mm:ss，选填", "2025-01-15 10:00:00"),
                ImportColumnDef.of("签约人用户名", F_SIGNED_BY, "已存在的用户名，选填", "sales01"),
                ImportColumnDef.of("票款勾稽", F_INVOICE_BEFORE, "true / false，留空默认 true", "true"),
                ImportColumnDef.of("备注", F_REMARK, "补充说明", "期初未结项目")
        );
    }

    @Override
    public String notes() {
        return "1. 带 * 为必填项；2. 单次最多 2000 行；3. 客户、销售经理、签约人须已存在（先导入客户档案与用户）；"
                + "4. 项目直接置为审批通过态，不进入审批流；5. 期初项目三个累计金额初始为 0，由后续期初单据导入滚入。";
    }

    @Override
    public ValidateResult validate(List<Map<String, String>> rows, Long operatorId) {
        List<RowError> errors = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> row = rows.get(i);
            int rowNo = i + 1;
            StringBuilder err = new StringBuilder();
            String name = val(row, F_NAME);
            if (name.isEmpty()) {
                err.append("项目名称必填；");
            } else if (!names.add(name)) {
                err.append("批内项目名称重复；");
            }
            String customer = val(row, F_CUSTOMER);
            if (customer.isEmpty()) {
                err.append("客户名称必填；");
            } else if (customerMapper.selectCount(new LambdaQueryWrapper<Customer>()
                    .eq(Customer::getCustomerName, customer)) == 0) {
                err.append("客户【" + customer + "】不存在；");
            }
            String manager = val(row, F_MANAGER);
            if (manager.isEmpty()) {
                err.append("销售经理用户名必填；");
            } else if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, manager)) == 0) {
                err.append("销售经理【" + manager + "】不存在；");
            }
            String amt = val(row, F_CONTRACT_AMOUNT);
            if (amt.isEmpty()) {
                err.append("合同金额必填；");
            } else {
                try {
                    if (new BigDecimal(amt).compareTo(BigDecimal.ZERO) < 0) err.append("合同金额不能为负；");
                } catch (NumberFormatException e) {
                    err.append("合同金额格式错误；");
                }
            }
            String cs = val(row, F_CONTRACT_STATUS);
            if (!cs.isEmpty() && !"UNSIGNED".equals(cs) && !"SIGNED".equals(cs)) {
                err.append("合同状态只能是 UNSIGNED/SIGNED；");
            }
            String signedAt = val(row, F_SIGNED_AT);
            if (!signedAt.isEmpty()) {
                try { LocalDateTime.parse(signedAt, DTF); } catch (DateTimeParseException e) {
                    err.append("签约日期格式应为 yyyy-MM-dd HH:mm:ss；");
                }
            }
            String signedBy = val(row, F_SIGNED_BY);
            if (!signedBy.isEmpty() && userMapper.selectCount(new LambdaQueryWrapper<User>()
                    .eq(User::getUsername, signedBy)) == 0) {
                err.append("签约人【" + signedBy + "】不存在；");
            }
            String ib = val(row, F_INVOICE_BEFORE);
            if (!ib.isEmpty() && !"true".equalsIgnoreCase(ib) && !"false".equalsIgnoreCase(ib)) {
                err.append("票款勾稽只能是 true/false；");
            }
            if (err.length() > 0) errors.add(new RowError(rowNo, err.toString()));
        }
        return errors.isEmpty() ? ValidateResult.pass() : ValidateResult.fail(errors);
    }

    @Override
    public int commit(List<Map<String, String>> rows, Long operatorId, String batchNo) {
        int n = 0;
        for (Map<String, String> row : rows) {
            Customer customer = customerMapper.selectOne(new LambdaQueryWrapper<Customer>()
                    .eq(Customer::getCustomerName, val(row, F_CUSTOMER)).last("LIMIT 1"));
            User manager = userMapper.selectOne(new LambdaQueryWrapper<User>()
                    .eq(User::getUsername, val(row, F_MANAGER)).last("LIMIT 1"));

            Project p = new Project();
            p.setProjectName(val(row, F_NAME));
            p.setCustomerId(customer == null ? 0L : customer.getId());
            p.setSalesManagerId(manager == null ? null : manager.getId());
            String cs = val(row, F_CONTRACT_STATUS);
            p.setContractStatus(cs.isEmpty() ? "SIGNED" : cs);
            p.setContractAmount(new BigDecimal(val(row, F_CONTRACT_AMOUNT)));
            String signedAt = val(row, F_SIGNED_AT);
            if (!signedAt.isEmpty()) p.setContractSignedAt(LocalDateTime.parse(signedAt, DTF));
            String signedBy = val(row, F_SIGNED_BY);
            if (!signedBy.isEmpty()) {
                User signer = userMapper.selectOne(new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, signedBy).last("LIMIT 1"));
                if (signer != null) p.setContractSignedBy(signer.getId());
            }
            String ib = val(row, F_INVOICE_BEFORE);
            p.setAllowInvoiceBeforeReceipt(ib.isEmpty() ? Boolean.TRUE : Boolean.valueOf(ib));
            // 期初直接审批通过，不拉 Flowable
            p.setApprovalStatus("APPROVED");
            p.setApplicantUsername(manager == null ? null : manager.getUsername());
            p.setCollectedAmount(BigDecimal.ZERO);
            p.setInvoicedAmount(BigDecimal.ZERO);
            p.setReceivableAmount(BigDecimal.ZERO);
            p.setContractDesc(val(row, F_REMARK));
            p.setCreatedBy(operatorId);
            p.setExt(UserImportHandler.importExt(batchNo));
            projectMapper.insert(p);
            // 项目编号：PJ-日期-ID（与 ProjectServiceImpl.generateProjectNo 同规则）
            p.setProjectNo(String.format("PJ-%s-%04d",
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")), p.getId()));
            projectMapper.updateById(p);
            n++;
        }
        return n;
    }

    private static String val(Map<String, String> row, String field) {
        String v = row.get(field);
        return v == null ? "" : v.trim();
    }
}
