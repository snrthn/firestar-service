package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.firestar.dto.LevelVO;
import com.firestar.dto.LevelRequest;
import com.firestar.entity.Level;
import com.firestar.entity.User;
import com.firestar.exception.BizException;
import com.firestar.mapper.LevelMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.service.LevelService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 职级管理实现。纪律同 PostServiceImpl：走 BaseMapper，零手写 SQL；
 * 业务校验失败抛 BizException，由全局异常处理统一转 Result。
 */
@Service
public class LevelServiceImpl implements LevelService {

    /** 分页默认每页条数（前端分页器默认规格一致） */
    private static final long DEFAULT_PAGE_SIZE = 10L;
    /** 单页上限，与 MybatisPlusConfig.MAX_PAGE_SIZE 对齐 */
    private static final long MAX_PAGE_SIZE = 500L;
    /** 职级编码格式：字母开头，可含字母、数字、下划线 */
    private static final String LEVEL_CODE_PATTERN = "^[A-Za-z][A-Za-z0-9_]*$";

    private final LevelMapper levelMapper;
    private final UserMapper userMapper;

    public LevelServiceImpl(LevelMapper levelMapper, UserMapper userMapper) {
        this.levelMapper = levelMapper;
        this.userMapper = userMapper;
    }

    @Override
    public IPage<LevelVO> pageLevels(Integer pageNum, Integer pageSize, String levelName, String levelCode, String status) {
        long current = pageNum == null || pageNum < 1 ? 1 : pageNum;
        long size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        // trim 必须先落到局部变量：wrapper.like(condition, column, xxx.trim()) 的参数
        // 是渴求求值，condition=false 时 trim() 也已经执行过，null 直接 NPE
        String nameKw = levelName == null || levelName.isBlank() ? null : levelName.trim();
        String codeKw = levelCode == null || levelCode.isBlank() ? null : levelCode.trim();
        String statusEq = status == null || status.isBlank() ? null : status.trim();
        Page<Level> page = levelMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<Level>()
                        .like(nameKw != null, Level::getLevelName, nameKw)
                        .like(codeKw != null, Level::getLevelCode, codeKw)
                        .eq(statusEq != null, Level::getStatus, statusEq)
                        .orderByAsc(Level::getLevelNo)
                        .orderByAsc(Level::getId));
        Page<LevelVO> voPage = new Page<>(current, size);
        voPage.setTotal(page.getTotal());
        List<LevelVO> vos = new ArrayList<>(page.getRecords().size());
        for (Level l : page.getRecords()) {
            vos.add(toVO(l));
        }
        voPage.setRecords(vos);
        return voPage;
    }

    @Override
    public Long createLevel(LevelRequest req) {
        Level level = new Level();
        applyForm(level, req, null);
        levelMapper.insert(level);
        return level.getId();
    }

    @Override
    public void updateLevel(Long id, LevelRequest req) {
        Level exist = levelMapper.selectById(id);
        if (exist == null) {
            throw new BizException("职级不存在");
        }
        String code = req.getLevelCode() == null ? "" : req.getLevelCode().trim();
        if (!code.isEmpty() && !code.equals(exist.getLevelCode())) {
            throw new BizException("职级编码创建后不可修改");
        }
        Level level = new Level();
        level.setId(id);
        applyForm(level, req, id);
        levelMapper.updateById(level);
    }

    @Override
    public void deleteLevel(Long id) {
        Level exist = levelMapper.selectById(id);
        if (exist == null) {
            throw new BizException("职级不存在");
        }
        Long userCount = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getLevelCode, exist.getLevelCode()));
        if (userCount != null && userCount > 0) {
            throw new BizException("该职级下有关联用户，请先调整用户职级");
        }
        levelMapper.deleteById(id);
    }

    @Override
    public List<LevelVO> listOptions() {
        List<Level> list = levelMapper.selectList(new LambdaQueryWrapper<Level>()
                .eq(Level::getStatus, "ENABLED")
                .orderByAsc(Level::getLevelNo)
                .orderByAsc(Level::getId));
        List<LevelVO> vos = new ArrayList<>(list.size());
        for (Level l : list) {
            vos.add(toVO(l));
        }
        return vos;
    }

    // ===== 私有辅助 =====

    private LevelVO toVO(Level l) {
        LevelVO vo = new LevelVO();
        vo.setId(l.getId());
        vo.setLevelCode(l.getLevelCode());
        vo.setLevelName(l.getLevelName());
        vo.setLevelNo(l.getLevelNo());
        vo.setSortNo(l.getSortNo());
        vo.setStatus(l.getStatus());
        vo.setRemark(l.getRemark());
        return vo;
    }

    /** 表单字段 → 实体（null 走 DDL 默认值：levelNo=0 / sortNo=0 / status=ENABLED）；selfId 为编辑中的职级 ID（新增传 null） */
    private void applyForm(Level level, LevelRequest req, Long selfId) {
        String levelCode = req.getLevelCode() == null ? "" : req.getLevelCode().trim();
        String levelName = req.getLevelName() == null ? "" : req.getLevelName().trim();
        if (levelCode.isEmpty()) {
            throw new BizException("职级编码不能为空");
        }
        if (!levelCode.matches(LEVEL_CODE_PATTERN)) {
            throw new BizException("职级编码必须以字母开头，只能包含字母、数字、下划线");
        }
        if (levelName.isEmpty()) {
            throw new BizException("职级名称不能为空");
        }
        Long codeDup = levelMapper.selectCount(new LambdaQueryWrapper<Level>()
                .eq(Level::getLevelCode, levelCode)
                .ne(selfId != null, Level::getId, selfId));
        if (codeDup != null && codeDup > 0) {
            throw new BizException("职级编码已存在");
        }
        Long nameDup = levelMapper.selectCount(new LambdaQueryWrapper<Level>()
                .eq(Level::getLevelName, levelName)
                .ne(selfId != null, Level::getId, selfId));
        if (nameDup != null && nameDup > 0) {
            throw new BizException("职级名称已存在");
        }
        Integer levelNo = req.getLevelNo() == null ? 0 : req.getLevelNo();
        if (levelNo < 0) {
            throw new BizException("层级数值不能为负");
        }
        String status = req.getStatus() == null || req.getStatus().isBlank() ? "ENABLED" : req.getStatus();
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw new BizException("职级状态仅支持 ENABLED / DISABLED");
        }
        level.setLevelCode(levelCode);
        level.setLevelName(levelName);
        level.setLevelNo(levelNo);
        level.setSortNo(req.getSortNo() == null ? 0 : req.getSortNo());
        level.setStatus(status);
        level.setRemark(req.getRemark());
    }
}
