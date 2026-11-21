package com.firestar.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.dto.ImportColumnDef;
import com.firestar.entity.Org;
import com.firestar.entity.User;
import com.firestar.mapper.OrgMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.service.ImportHandler;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 期初组织导入 Handler（P1，方案 v3 §5）。
 *
 * <p>树形导入：模板用「父级组织名」引用，留空=根部门。要求模板按「父在子前」的顺序排列——
 * 本批新建的组织在 commit 中逐行 insert 后登记 name→id，后续子行即可引用。
 * 校验阶段拦住：组织名必填、批内重名、同级重名、父级不存在（库里或本批内）。</p>
 */
@Component
public class OrgImportHandler implements ImportHandler {

    private static final String F_NAME = "orgName";
    private static final String F_PARENT = "parentName";
    private static final String F_SORT = "sortNo";
    private static final String F_LEADER = "leaderUsername";
    private static final String F_STATUS = "status";

    private final OrgMapper orgMapper;
    private final UserMapper userMapper;

    public OrgImportHandler(OrgMapper orgMapper, UserMapper userMapper) {
        this.orgMapper = orgMapper;
        this.userMapper = userMapper;
    }

    @Override
    public String key() {
        return "org_opening";
    }

    @Override
    public String displayName() {
        return "期初组织";
    }

    @Override
    public String sheetName() {
        return "组织";
    }

    @Override
    public List<ImportColumnDef> columns() {
        return List.of(
                ImportColumnDef.required("组织名称", F_NAME, "必填，同一上级下不可重名", "财务部"),
                ImportColumnDef.of("父级组织名", F_PARENT, "留空=根部门；已存在或本批已建", "总公司"),
                ImportColumnDef.of("排序", F_SORT, "数字，留空默认 0", "10"),
                ImportColumnDef.of("负责人用户名", F_LEADER, "已存在的用户名，选填", "zhangsan"),
                ImportColumnDef.of("状态", F_STATUS, "ENABLED / DISABLED，留空默认 ENABLED", "ENABLED")
        );
    }

    @Override
    public String notes() {
        return "1. 带 * 为必填项；2. 单次最多 2000 行；3. 请按「父组织在子组织之前」的顺序排列；"
                + "4. 父级组织名可引用已存在的组织，或本批中前面行新建的组织；5. 同一上级下组织名不可重复。";
    }

    @Override
    public ValidateResult validate(List<Map<String, String>> rows, Long operatorId) {
        List<RowError> errors = new ArrayList<>();
        Set<String> batchNames = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> row = rows.get(i);
            int rowNo = i + 1;
            StringBuilder err = new StringBuilder();
            String name = val(row, F_NAME);
            String parent = val(row, F_PARENT);
            if (name.isEmpty()) {
                err.append("组织名称必填；");
            } else {
                if (!batchNames.add(name)) {
                    err.append("批内组织名称重复；");
                }
                // 同级重名：库里同 parent 下是否存在（parent 名转 id 在 commit 做，这里只按名粗判）
                Long parentId = resolveExistingParentId(parent);
                if (parentId != null && orgMapper.selectCount(new LambdaQueryWrapper<Org>()
                        .eq(Org::getParentId, parentId).eq(Org::getOrgName, name)) > 0) {
                    err.append("该上级下已存在同名组织；");
                }
                if (parent.isEmpty() && orgMapper.selectCount(new LambdaQueryWrapper<Org>()
                        .eq(Org::getParentId, 0L).eq(Org::getOrgName, name)) > 0) {
                    err.append("根部门下已存在同名组织；");
                }
            }
            if (!parent.isEmpty() && !batchNames.contains(parent) && resolveExistingParentId(parent) == null) {
                // 允许引用本批前面行新建的父级（batchNames 只累计已处理的行）
                err.append("父级组织【" + parent + "】不存在（需已存在或在本批前序行创建）；");
            }
            String sort = val(row, F_SORT);
            if (!sort.isEmpty()) {
                try { Integer.parseInt(sort); } catch (NumberFormatException e) { err.append("排序必须是整数；"); }
            }
            String status = val(row, F_STATUS);
            if (!status.isEmpty() && !"ENABLED".equals(status) && !"DISABLED".equals(status)) {
                err.append("状态只能是 ENABLED/DISABLED；");
            }
            String leader = val(row, F_LEADER);
            if (!leader.isEmpty() && userMapper.selectCount(new LambdaQueryWrapper<User>()
                    .eq(User::getUsername, leader)) == 0) {
                err.append("负责人用户名【" + leader + "】不存在；");
            }
            if (err.length() > 0) errors.add(new RowError(rowNo, err.toString()));
        }
        return errors.isEmpty() ? ValidateResult.pass() : ValidateResult.fail(errors);
    }

    @Override
    public int commit(List<Map<String, String>> rows, Long operatorId, String batchNo) {
        // name→id：先填库里已存在的组织，再逐行登记本批新建的
        Map<String, Long> nameToId = new HashMap<>();
        for (Org o : orgMapper.selectList(null)) {
            if (o.getOrgName() != null) nameToId.putIfAbsent(o.getOrgName(), o.getId());
        }
        int n = 0;
        for (Map<String, String> row : rows) {
            String name = val(row, F_NAME);
            String parent = val(row, F_PARENT);
            Long parentId = parent.isEmpty() ? 0L : nameToId.get(parent);
            if (parentId == null) parentId = 0L; // validate 已保证可解析，防御兜底

            Org org = new Org();
            org.setOrgName(name);
            org.setParentId(parentId);
            String sort = val(row, F_SORT);
            org.setSortNo(sort.isEmpty() ? 0 : Integer.parseInt(sort));
            String status = val(row, F_STATUS);
            org.setStatus(status.isEmpty() ? "ENABLED" : status);
            String leader = val(row, F_LEADER);
            if (!leader.isEmpty()) {
                User u = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, leader).last("LIMIT 1"));
                if (u != null) org.setLeaderId(u.getId());
            }
            org.setCreatedBy(operatorId);
            org.setExt(UserImportHandler.importExt(batchNo));
            orgMapper.insert(org);
            nameToId.put(name, org.getId()); // 供本批后续子行引用
            n++;
        }
        return n;
    }

    private Long resolveExistingParentId(String parentName) {
        if (parentName == null || parentName.isEmpty()) return null;
        Org o = orgMapper.selectOne(new LambdaQueryWrapper<Org>()
                .eq(Org::getOrgName, parentName).last("LIMIT 1"));
        return o == null ? null : o.getId();
    }

    private static String val(Map<String, String> row, String field) {
        String v = row.get(field);
        return v == null ? "" : v.trim();
    }
}
