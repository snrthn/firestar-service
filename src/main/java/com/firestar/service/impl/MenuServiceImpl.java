package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.firestar.common.MenuTreeUtil;
import com.firestar.dto.MenuRequest;
import com.firestar.dto.MenuVO;
import com.firestar.entity.Menu;
import com.firestar.annotation.Auditable;
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
    @Auditable(operationType = "CREATE", targetType = "MENU", targetIdSpEl = "#result", entityClass = Menu.class, fields = {"menuName", "parentId", "routePath", "component", "permCode", "menuType", "sortNo", "visible", "status", "icon"})
    public Long create(MenuRequest req) {
        Menu menu = new Menu();
        applyForm(menu, req);
        menuMapper.insert(menu);
        return menu.getId();
    }

    @Override
    @Auditable(operationType = "UPDATE", targetType = "MENU", targetIdSpEl = "#id", entityClass = Menu.class, fields = {"menuName", "parentId", "routePath", "component", "permCode", "menuType", "sortNo", "visible", "status", "icon"})
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
    @Auditable(operationType = "DELETE", targetType = "MENU", targetIdSpEl = "#id", entityClass = Menu.class, fields = {"menuName", "routePath", "menuType", "status"})
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

    /** 表单字段 → 实体（null 走 DDL 默认值：menuType=MENU / visible=1 / status=ENABLED / sortNo=0 / isCache=0） */
    private void applyForm(Menu menu, MenuRequest req) {
        Long parentId = req.getParentId() == null ? 0L : req.getParentId();
        String menuName = req.getMenuName() == null ? "" : req.getMenuName().trim();
        String menuType = req.getMenuType() == null ? "MENU" : req.getMenuType();
        if (menuName.isEmpty()) {
            throw new BizException("菜单名称不能为空");
        }
        if (!"CATALOG".equals(menuType) && !"MENU".equals(menuType)
                && !"BUTTON".equals(menuType) && !"LINK".equals(menuType)) {
            throw new BizException("菜单类型仅支持 CATALOG / MENU / BUTTON / LINK");
        }
        String status = req.getStatus() == null || req.getStatus().isBlank() ? "ENABLED" : req.getStatus();
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw new BizException("菜单状态仅支持 ENABLED / DISABLED");
        }
        // 路由地址：MENU 必填；CATALOG/BUTTON/LINK 不参与 SPA 路由，拒绝误填
        // （外链地址走 linkUrl 单独校验，防 http:// 混进 routePath 被 router.push 当相对路径）
        if ("MENU".equals(menuType) && (req.getRoutePath() == null || req.getRoutePath().isBlank())) {
            throw new BizException("菜单类型为「菜单」时，路由地址必填");
        }
        if (!"MENU".equals(menuType) && req.getRoutePath() != null && !req.getRoutePath().isBlank()) {
            throw new BizException("仅「菜单」类型可填路由地址（外链请填链接地址）");
        }
        if ("MENU".equals(menuType) && req.getRoutePath() != null
                && req.getRoutePath().trim().toLowerCase().startsWith("http")) {
            throw new BizException("路由地址请填站内相对路径；外部链接请选择「外链」类型填链接地址");
        }
        // 权限编码：BUTTON 必填（按钮型菜单的唯一存在意义就是权限节点）；
        // 其它类型可空（接口级权限由 @RequirePerm 兜底，见方案 B）
        String permCode = req.getPermCode() == null ? null : req.getPermCode().trim();
        if ("BUTTON".equals(menuType) && (permCode == null || permCode.isEmpty())) {
            throw new BizException("菜单类型为「按钮」时，权限编码必填");
        }
        // 外链地址：LINK 必填 + 协议白名单（只放行 http/https，天然拦截
        // javascript:/data: 等危险协议）+ 长度兜底（列宽 500）。
        // 前端 menu.vue 有同款正则 rule，此处是后端强校验——绕过前端直发也要挡住。
        String linkUrl = req.getLinkUrl() == null ? null : req.getLinkUrl().trim();
        if ("LINK".equals(menuType)) {
            if (linkUrl == null || linkUrl.isEmpty()) {
                throw new BizException("菜单类型为「外链」时，链接地址必填");
            }
            if (linkUrl.length() > 500) {
                throw new BizException("链接地址长度不能超过 500 个字符");
            }
            if (!linkUrl.matches("(?i)^https?://\\S+$")) {
                throw new BizException("链接地址须以 http:// 或 https:// 开头");
            }
        } else if (linkUrl != null && !linkUrl.isEmpty()) {
            throw new BizException("仅「外链」类型可填链接地址");
        }
        // 缓存开关：仅 MENU 有意义，归一为 0/1（null→0；目录/按钮/外链不缓存）
        int isCache = req.getIsCache() == null ? 0 : req.getIsCache();
        if (isCache != 0 && isCache != 1) {
            throw new BizException("是否缓存仅支持 0 / 1");
        }
        if (!"MENU".equals(menuType) && isCache == 1) {
            isCache = 0;
        }
        if (parentId != 0) {
            Menu parent = menuMapper.selectById(parentId);
            if (parent == null) {
                throw new BizException("父菜单不存在");
            }
        }
        menu.setParentId(parentId);
        menu.setMenuName(menuName);
        menu.setRoutePath(req.getRoutePath() == null || req.getRoutePath().isBlank() ? null : req.getRoutePath().trim());
        menu.setLinkUrl(linkUrl == null || linkUrl.isEmpty() ? null : linkUrl);
        menu.setComponent(req.getComponent() == null || req.getComponent().isBlank() ? null : req.getComponent().trim());
        menu.setPermCode(permCode == null || permCode.isEmpty() ? null : permCode);
        menu.setMenuType(menuType);
        menu.setSortNo(req.getSortNo() == null ? 0 : req.getSortNo());
        menu.setVisible(req.getVisible() == null ? 1 : req.getVisible());
        menu.setStatus(status);
        menu.setIsCache(isCache);
        // 图标：直接透传（可空）。空字符串统一归一为 null，避免库里残留 '' 导致侧栏不兜底。
        String icon = req.getIcon();
        menu.setIcon(icon == null || icon.isBlank() ? null : icon.trim());
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
