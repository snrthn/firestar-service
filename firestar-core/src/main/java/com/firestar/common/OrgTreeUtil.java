package com.firestar.common;

import com.firestar.dto.OrgVO;
import com.firestar.entity.Org;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 组织架构树构建工具（菜单树 MenuTreeUtil 的 org 版本）。
 * 单处维护父子挂接规则：parentId 为 0 / null / 不在集合内的视为根节点，
 * children 按 sortNo 升序（调用方传入顺序即展示顺序）。
 * leaderName 由调用方传入 id→姓名映射回填，工具本身不碰数据库。
 */
public final class OrgTreeUtil {

    private OrgTreeUtil() {
    }

    public static List<OrgVO> build(List<Org> orgs, Map<Long, String> leaderNames) {
        Map<Long, OrgVO> voMap = new LinkedHashMap<>();
        for (Org o : orgs) {
            OrgVO vo = new OrgVO();
            vo.setId(o.getId());
            vo.setParentId(o.getParentId());
            vo.setOrgName(o.getOrgName());
            vo.setLeaderId(o.getLeaderId());
            vo.setLeaderName(o.getLeaderId() == null ? ""
                    : leaderNames.getOrDefault(o.getLeaderId(), ""));
            vo.setSortNo(o.getSortNo());
            vo.setStatus(o.getStatus());
            voMap.put(o.getId(), vo);
        }
        List<OrgVO> roots = new ArrayList<>();
        for (OrgVO vo : voMap.values()) {
            if (vo.getParentId() != null && vo.getParentId() != 0 && voMap.containsKey(vo.getParentId())) {
                OrgVO parent = voMap.get(vo.getParentId());
                if (parent.getChildren() == null) {
                    parent.setChildren(new ArrayList<>());
                }
                parent.getChildren().add(vo);
            } else {
                roots.add(vo);
            }
        }
        roots.sort(Comparator.comparingInt(v -> v.getSortNo() == null ? 0 : v.getSortNo()));
        return roots;
    }
}
