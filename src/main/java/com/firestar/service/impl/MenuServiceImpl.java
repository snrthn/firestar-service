package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.firestar.common.MenuTreeUtil;
import com.firestar.dto.MenuRequest;
import com.firestar.dto.MenuVO;
import com.firestar.entity.Menu;
import com.firestar.exception.BizException;
import com.firestar.mapper.MenuMapper;
import com.firestar.service.MenuService;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 菜单管理实现。纪律同 AuthServiceImpl：走 BaseMapper，零手写 SQL；
 * 业务校验失败抛 BizException，由全局异常处理统一转 Result。
 */
@Service
public class MenuServiceImpl implements MenuService {

    private final MenuMapper menuMapper;

    public MenuServiceImpl(MenuMapper menuMapper) {
        this.menuMapper = menuMapper;
    }

    @Override
    public List<MenuVO> listTree() {
        List<Menu> menus = menuMapper.selectList(new LambdaQueryWrapper<Menu>()
                .orderByAsc(Menu::getSortNo)
                .orderByAsc(Menu::getId));
        return MenuTreeUtil.build(menus);
    }

    @Override
    public Long create(MenuRequest req) {
        Menu menu = new Menu();
        applyForm(menu, req);
        menuMapper.insert(menu);
        return menu.getId();
    }

    @Override
    public void update(Long id, MenuRequest req) {
        Menu exist = menuMapper.selectById(id);
        if (exist == null) {
            throw new BizException("菜单不存在");
        }
        Menu menu = new Menu();
        menu.setId(id);
        applyForm(menu, req);
        // 防死循环：父菜单不能是自己，也不能是自己的子孙
        validateNoCycle(id, menu.getParentId());
        menuMapper.updateById(menu);
    }

    @Override
    public void delete(Long id) {
        Menu exist = menuMapper.selectById(id);
        if (exist == null) {
            throw new BizException("菜单不存在");
        }
        Long childCount = menuMapper.selectCount(new LambdaQueryWrapper<Menu>()
                .eq(Menu::getParentId, id));
        if (childCount != null && childCount > 0) {
            throw new BizException("该菜单存在子菜单，请先删除或移走子菜单");
        }
        menuMapper.deleteById(id);
    }

    @Override
    public void move(Long id, String direction) {
        if (!"up".equals(direction) && !"down".equals(direction)) {
            throw new BizException("排序方向仅支持 up / down");
        }
        Menu current = menuMapper.selectById(id);
        if (current == null) {
            throw new BizException("菜单不存在");
        }
        List<Menu> siblings = menuMapper.selectList(new LambdaQueryWrapper<Menu>()
                .eq(Menu::getParentId, current.getParentId() == null ? 0L : current.getParentId())
                .orderByAsc(Menu::getSortNo)
                .orderByAsc(Menu::getId));
        int idx = -1;
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).getId().equals(id)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            throw new BizException("菜单不存在");
        }
        int target = "up".equals(direction) ? idx - 1 : idx + 1;
        if (target < 0) {
            throw new BizException(400, "已在同级首位，无法继续上移");
        }
        if (target >= siblings.size()) {
            throw new BizException(400, "已在同级末位，无法继续下移");
        }
        Menu other = siblings.get(target);
        int currentSort = current.getSortNo() == null ? 0 : current.getSortNo();
        int otherSort = other.getSortNo() == null ? 0 : other.getSortNo();
        menuMapper.update(null, new LambdaUpdateWrapper<Menu>()
                .eq(Menu::getId, current.getId())
                .set(Menu::getSortNo, otherSort));
        menuMapper.update(null, new LambdaUpdateWrapper<Menu>()
                .eq(Menu::getId, other.getId())
                .set(Menu::getSortNo, currentSort));
    }

    // ===== 私有辅助 =====

    /** 表单字段 → 实体（null 走 DDL 默认值：menuType=MENU / visible=1 / status=ENABLED / sortNo=0） */
    private void applyForm(Menu menu, MenuRequest req) {
        Long parentId = req.getParentId() == null ? 0L : req.getParentId();
        String menuName = req.getMenuName() == null ? "" : req.getMenuName().trim();
        String menuType = req.getMenuType() == null ? "MENU" : req.getMenuType();
        if (menuName.isEmpty()) {
            throw new BizException("菜单名称不能为空");
        }
        if (!"CATALOG".equals(menuType) && !"MENU".equals(menuType)) {
            throw new BizException("菜单类型仅支持 CATALOG / MENU");
        }
        if ("MENU".equals(menuType) && (req.getRoutePath() == null || req.getRoutePath().isBlank())) {
            throw new BizException("菜单类型为「菜单」时，路由地址必填");
        }
        if (parentId != 0) {
            Menu parent = menuMapper.selectById(parentId);
            if (parent == null) {
                throw new BizException("父菜单不存在");
            }
        }
        menu.setParentId(parentId);
        menu.setMenuName(menuName);
        menu.setRoutePath(req.getRoutePath());
        menu.setComponent(req.getComponent());
        menu.setPermCode(req.getPermCode());
        menu.setMenuType(menuType);
        menu.setSortNo(req.getSortNo() == null ? 0 : req.getSortNo());
        menu.setVisible(req.getVisible() == null ? 1 : req.getVisible());
        menu.setStatus(req.getStatus() == null || req.getStatus().isBlank() ? "ENABLED" : req.getStatus());
    }

    /**
     * 防死循环校验：父菜单链上溯遇到自身即拒（数据层面已有环时用 visited 兜底跳出）。
     * create 场景 id 为 null，由调用方跳过。
     */
    private void validateNoCycle(Long id, Long parentId) {
        if (id == null || parentId == null || parentId == 0) {
            return;
        }
        if (parentId.equals(id)) {
            throw new BizException("父菜单不能是菜单自身");
        }
        Set<Long> visited = new HashSet<>();
        Long cursor = parentId;
        while (cursor != null && cursor != 0) {
            if (cursor.equals(id)) {
                throw new BizException("父菜单不能设置为自己的子菜单，会造成死循环");
            }
            if (!visited.add(cursor)) {
                break;
            }
            Menu parent = menuMapper.selectById(cursor);
            if (parent == null) {
                break;
            }
            cursor = parent.getParentId();
        }
    }
}
