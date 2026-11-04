package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.firestar.common.OrgTreeUtil;
import com.firestar.dto.OrgRequest;
import com.firestar.dto.OrgVO;
import com.firestar.entity.Org;
import com.firestar.entity.User;
import com.firestar.exception.BizException;
import com.firestar.mapper.OrgMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.service.OrgService;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 组织架构管理实现。纪律同 MenuServiceImpl：走 BaseMapper，零手写 SQL；
 * 业务校验失败抛 BizException，由全局异常处理统一转 Result。
 */
@Service
public class OrgServiceImpl implements OrgService {

    private final OrgMapper orgMapper;
    private final UserMapper userMapper;

    public OrgServiceImpl(OrgMapper orgMapper, UserMapper userMapper) {
        this.orgMapper = orgMapper;
        this.userMapper = userMapper;
    }

    @Override
    public List<OrgVO> listTree() {
        List<Org> orgs = orgMapper.selectList(new LambdaQueryWrapper<Org>()
                .orderByAsc(Org::getSortNo)
                .orderByAsc(Org::getId));
        // 收集负责人 ID，一次批量查询回填姓名（避免 N+1）
        Set<Long> leaderIds = new HashSet<>();
        for (Org o : orgs) {
            if (o.getLeaderId() != null) {
                leaderIds.add(o.getLeaderId());
            }
        }
        Map<Long, String> leaderNames = new HashMap<>();
        if (!leaderIds.isEmpty()) {
            List<User> leaders = userMapper.selectBatchIds(leaderIds);
            for (User u : leaders) {
                leaderNames.put(u.getId(), u.getNickname());
            }
        }
        return OrgTreeUtil.build(orgs, leaderNames);
    }

    @Override
    public Long create(OrgRequest req) {
        Org org = new Org();
        applyForm(org, req, null);
        orgMapper.insert(org);
        return org.getId();
    }

    @Override
    public void update(Long id, OrgRequest req) {
        Org exist = orgMapper.selectById(id);
        if (exist == null) {
            throw new BizException("部门不存在");
        }
        Org org = new Org();
        org.setId(id);
        applyForm(org, req, id);
        // 防死循环：上级部门不能是自己，也不能是自己的子孙
        validateNoCycle(id, org.getParentId());
        orgMapper.updateById(org);
        // MP updateById 默认忽略 null 字段：清空负责人时 leader_id 不会被写进 UPDATE，
        // 必须用 wrapper 显式置空，否则「清除负责人」永远存不上
        if (org.getLeaderId() == null) {
            orgMapper.update(null, new LambdaUpdateWrapper<Org>()
                    .eq(Org::getId, id)
                    .set(Org::getLeaderId, null));
        }
    }

    @Override
    public void delete(Long id) {
        Org exist = orgMapper.selectById(id);
        if (exist == null) {
            throw new BizException("部门不存在");
        }
        Long childCount = orgMapper.selectCount(new LambdaQueryWrapper<Org>()
                .eq(Org::getParentId, id));
        if (childCount != null && childCount > 0) {
            throw new BizException("该部门存在子部门，请先删除或移走子部门");
        }
        Long userCount = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getDeptId, id));
        if (userCount != null && userCount > 0) {
            throw new BizException("该部门下有关联用户，请先调整用户所属部门");
        }
        orgMapper.deleteById(id);
    }

    // ===== 私有辅助 =====

    /** 表单字段 → 实体（null 走 DDL 默认值：parentId=0 / sortNo=0 / status=ENABLED）；selfId 为编辑中的部门 ID（新增传 null） */
    private void applyForm(Org org, OrgRequest req, Long selfId) {
        Long parentId = req.getParentId() == null ? 0L : req.getParentId();
        String orgName = req.getOrgName() == null ? "" : req.getOrgName().trim();
        if (orgName.isEmpty()) {
            throw new BizException("部门名称不能为空");
        }
        String status = req.getStatus() == null || req.getStatus().isBlank() ? "ENABLED" : req.getStatus();
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw new BizException("部门状态仅支持 ENABLED / DISABLED");
        }
        if (parentId != 0) {
            Org parent = orgMapper.selectById(parentId);
            if (parent == null) {
                throw new BizException("上级部门不存在");
            }
        }
        // 同级同名校验：同一父部门下不允许重名（编辑时排除自身）
        Long dupCount = orgMapper.selectCount(new LambdaQueryWrapper<Org>()
                .eq(Org::getParentId, parentId)
                .eq(Org::getOrgName, orgName)
                .ne(selfId != null, Org::getId, selfId));
        if (dupCount != null && dupCount > 0) {
            throw new BizException("同一上级部门下已存在同名部门");
        }
        Long leaderId = req.getLeaderId();
        if (leaderId != null) {
            User leader = userMapper.selectById(leaderId);
            if (leader == null) {
                throw new BizException("部门负责人不存在");
            }
        }
        org.setParentId(parentId);
        org.setOrgName(orgName);
        org.setLeaderId(leaderId);
        org.setSortNo(req.getSortNo() == null ? 0 : req.getSortNo());
        org.setStatus(status);
    }

    /**
     * 防死循环校验：上级部门链上溯遇到自身即拒（数据层面已有环时用 visited 兜底跳出）。
     * create 场景 id 为 null，由调用方跳过。
     */
    private void validateNoCycle(Long id, Long parentId) {
        if (id == null || parentId == null || parentId == 0) {
            return;
        }
        if (parentId.equals(id)) {
            throw new BizException("上级部门不能是部门自身");
        }
        Set<Long> visited = new HashSet<>();
        Long cursor = parentId;
        while (cursor != null && cursor != 0) {
            if (cursor.equals(id)) {
                throw new BizException("上级部门不能设置为自己的子部门，会造成死循环");
            }
            if (!visited.add(cursor)) {
                break;
            }
            Org parent = orgMapper.selectById(cursor);
            if (parent == null) {
                break;
            }
            cursor = parent.getParentId();
        }
    }
}
