package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.firestar.dto.DictDataRequest;
import com.firestar.dto.DictDataVO;
import com.firestar.dto.DictTypeRequest;
import com.firestar.dto.DictTypeVO;
import com.firestar.entity.DictData;
import com.firestar.entity.DictType;
import com.firestar.exception.BizException;
import com.firestar.mapper.DictDataMapper;
import com.firestar.mapper.DictTypeMapper;
import com.firestar.service.DictService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 字典管理实现。纪律同 MenuServiceImpl：走 BaseMapper，零手写 SQL；
 * 业务校验失败抛 BizException，由全局异常处理统一转 Result。
 *
 * 关键业务规则：
 *  - typeCode / 所属类型创建后不可改（字典数据按 type_code 业务编码关联，
 *    改编码会产生孤儿数据）——update 时忽略请求体中的 typeCode；
 *  - typeCode 全局唯一（uk_tenant_dict_type_code），(typeCode,dictValue) 唯一
 *    （uk_tenant_dict_value），增改都查重；
 *  - 删类型前校验关联数据数，有数据拒删（RuoYi 同款行为）。
 */
@Service
public class DictServiceImpl implements DictService {

    /** 分页默认每页条数（前端分页器默认规格一致） */
    private static final long DEFAULT_PAGE_SIZE = 10L;
    /** 单页上限，与 MybatisPlusConfig.MAX_PAGE_SIZE 对齐（分页插件侧也会再截一次） */
    private static final long MAX_PAGE_SIZE = 500L;

    /** 前端 tag 样式白名单（与前端 dict-data.vue 下拉选项一致） */
    private static final List<String> DICT_STYLES = List.of("primary", "success", "info", "warning", "danger");

    private final DictTypeMapper dictTypeMapper;
    private final DictDataMapper dictDataMapper;

    public DictServiceImpl(DictTypeMapper dictTypeMapper, DictDataMapper dictDataMapper) {
        this.dictTypeMapper = dictTypeMapper;
        this.dictDataMapper = dictDataMapper;
    }

    // ===== 字典类型 =====

    @Override
    public IPage<DictTypeVO> listTypes(Integer pageNum, Integer pageSize) {
        long current = pageNum == null || pageNum < 1 ? 1 : pageNum;
        long size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        Page<DictType> page = dictTypeMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<DictType>().orderByAsc(DictType::getId));
        Page<DictTypeVO> voPage = new Page<>(current, size);
        voPage.setTotal(page.getTotal());
        List<DictTypeVO> vos = new ArrayList<>(page.getRecords().size());
        for (DictType t : page.getRecords()) {
            vos.add(toTypeVO(t));
        }
        voPage.setRecords(vos);
        return voPage;
    }

    @Override
    public List<DictTypeVO> listAllTypes() {
        List<DictType> types = dictTypeMapper.selectList(new LambdaQueryWrapper<DictType>()
                .orderByAsc(DictType::getId));
        List<DictTypeVO> vos = new ArrayList<>(types.size());
        for (DictType t : types) {
            vos.add(toTypeVO(t));
        }
        return vos;
    }

    @Override
    public Long createType(DictTypeRequest req) {
        applyTypeCheck(req, null);
        DictType type = new DictType();
        type.setTypeCode(req.getTypeCode().trim());
        type.setTypeName(req.getTypeName().trim());
        type.setStatus(req.getStatus() == null || req.getStatus().isBlank() ? "ENABLED" : req.getStatus());
        type.setRemark(req.getRemark());
        dictTypeMapper.insert(type);
        return type.getId();
    }

    @Override
    public void updateType(Long id, DictTypeRequest req) {
        DictType exist = dictTypeMapper.selectById(id);
        if (exist == null) {
            throw new BizException("字典类型不存在");
        }
        applyTypeCheck(req, id);
        // 只改可改字段；typeCode 一律以库中现存值为准（见类注释）
        DictType type = new DictType();
        type.setId(id);
        type.setTypeName(req.getTypeName().trim());
        type.setStatus(req.getStatus() == null || req.getStatus().isBlank() ? "ENABLED" : req.getStatus());
        type.setRemark(req.getRemark());
        dictTypeMapper.updateById(type);
    }

    @Override
    public void deleteType(Long id) {
        DictType exist = dictTypeMapper.selectById(id);
        if (exist == null) {
            throw new BizException("字典类型不存在");
        }
        Long dataCount = dictDataMapper.selectCount(new LambdaQueryWrapper<DictData>()
                .eq(DictData::getTypeCode, exist.getTypeCode()));
        if (dataCount != null && dataCount > 0) {
            throw new BizException("该字典类型下存在字典数据，请先删除字典数据");
        }
        dictTypeMapper.deleteById(id);
    }

    // ===== 字典数据 =====

    @Override
    public IPage<DictDataVO> listData(String typeCode, Integer pageNum, Integer pageSize) {
        long current = pageNum == null || pageNum < 1 ? 1 : pageNum;
        long size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        Page<DictDataVO> voPage = new Page<>(current, size);
        if (typeCode == null || typeCode.isBlank()) {
            // typeCode 缺失：返回空页而不是全量，与隐藏页「缺少类型参数」的提示配套
            voPage.setTotal(0);
            voPage.setRecords(new ArrayList<>());
            return voPage;
        }
        Page<DictData> page = dictDataMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<DictData>()
                        .eq(DictData::getTypeCode, typeCode)
                        .orderByAsc(DictData::getSortNo)
                        .orderByAsc(DictData::getId));
        voPage.setTotal(page.getTotal());
        List<DictDataVO> vos = new ArrayList<>(page.getRecords().size());
        for (DictData d : page.getRecords()) {
            vos.add(toDataVO(d));
        }
        voPage.setRecords(vos);
        return voPage;
    }

    @Override
    public Long createData(DictDataRequest req) {
        applyDataCheck(req, null);
        DictData data = new DictData();
        data.setTypeCode(req.getTypeCode().trim());
        data.setDictLabel(req.getDictLabel().trim());
        data.setDictValue(req.getDictValue().trim());
        data.setSortNo(req.getSortNo() == null ? 0 : req.getSortNo());
        data.setStyle(req.getStyle() == null || req.getStyle().isBlank() ? null : req.getStyle().trim());
        data.setIsDefault(req.getIsDefault() == null ? 0 : req.getIsDefault());
        data.setStatus(req.getStatus() == null || req.getStatus().isBlank() ? "ENABLED" : req.getStatus());
        data.setRemark(req.getRemark());
        dictDataMapper.insert(data);
        return data.getId();
    }

    @Override
    public void updateData(Long id, DictDataRequest req) {
        DictData exist = dictDataMapper.selectById(id);
        if (exist == null) {
            throw new BizException("字典数据不存在");
        }
        applyDataCheck(req, id);
        // typeCode 一律以库中现存值为准，不允许改所属类型（见类注释）
        DictData data = new DictData();
        data.setId(id);
        data.setDictLabel(req.getDictLabel().trim());
        data.setDictValue(req.getDictValue().trim());
        data.setSortNo(req.getSortNo() == null ? 0 : req.getSortNo());
        data.setStyle(req.getStyle() == null || req.getStyle().isBlank() ? null : req.getStyle().trim());
        data.setIsDefault(req.getIsDefault() == null ? 0 : req.getIsDefault());
        data.setStatus(req.getStatus() == null || req.getStatus().isBlank() ? "ENABLED" : req.getStatus());
        data.setRemark(req.getRemark());
        dictDataMapper.updateById(data);
    }

    @Override
    public void deleteData(Long id) {
        DictData exist = dictDataMapper.selectById(id);
        if (exist == null) {
            throw new BizException("字典数据不存在");
        }
        dictDataMapper.deleteById(id);
    }

    // ===== 私有辅助 =====

    /** 类型表单校验（excludeId 为修改时的自身 id，查重要排除） */
    private void applyTypeCheck(DictTypeRequest req, Long excludeId) {
        String typeCode = req.getTypeCode() == null ? "" : req.getTypeCode().trim();
        String typeName = req.getTypeName() == null ? "" : req.getTypeName().trim();
        if (typeCode.isEmpty()) {
            throw new BizException("字典类型编码不能为空");
        }
        if (!typeCode.matches("^[a-z][a-z0-9_]*$")) {
            throw new BizException("字典类型编码必须以小写字母开头，只能包含小写字母、数字、下划线");
        }
        if (typeName.isEmpty()) {
            throw new BizException("字典类型名称不能为空");
        }
        String status = req.getStatus() == null || req.getStatus().isBlank() ? "ENABLED" : req.getStatus();
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw new BizException("字典类型状态仅支持 ENABLED / DISABLED");
        }
        Long dupCount = dictTypeMapper.selectCount(new LambdaQueryWrapper<DictType>()
                .eq(DictType::getTypeCode, typeCode)
                .ne(excludeId != null, DictType::getId, excludeId));
        if (dupCount != null && dupCount > 0) {
            throw new BizException("字典类型编码已存在");
        }
    }

    /** 数据表单校验（excludeId 为修改时的自身 id，查重要排除） */
    private void applyDataCheck(DictDataRequest req, Long excludeId) {
        String typeCode = req.getTypeCode() == null ? "" : req.getTypeCode().trim();
        String dictLabel = req.getDictLabel() == null ? "" : req.getDictLabel().trim();
        String dictValue = req.getDictValue() == null ? "" : req.getDictValue().trim();
        if (typeCode.isEmpty()) {
            throw new BizException("字典类型编码不能为空");
        }
        DictType type = dictTypeMapper.selectOne(new LambdaQueryWrapper<DictType>()
                .eq(DictType::getTypeCode, typeCode));
        if (type == null) {
            throw new BizException("所属字典类型不存在");
        }
        if (dictLabel.isEmpty()) {
            throw new BizException("字典标签不能为空");
        }
        if (dictValue.isEmpty()) {
            throw new BizException("字典键值不能为空");
        }
        String style = req.getStyle() == null || req.getStyle().isBlank() ? null : req.getStyle().trim();
        if (style != null && !DICT_STYLES.contains(style)) {
            throw new BizException("字典样式仅支持 primary / success / info / warning / danger");
        }
        Integer isDefault = req.getIsDefault() == null ? 0 : req.getIsDefault();
        if (isDefault != 0 && isDefault != 1) {
            throw new BizException("是否默认仅支持 0 / 1");
        }
        String status = req.getStatus() == null || req.getStatus().isBlank() ? "ENABLED" : req.getStatus();
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw new BizException("字典数据状态仅支持 ENABLED / DISABLED");
        }
        Long dupCount = dictDataMapper.selectCount(new LambdaQueryWrapper<DictData>()
                .eq(DictData::getTypeCode, typeCode)
                .eq(DictData::getDictValue, dictValue)
                .ne(excludeId != null, DictData::getId, excludeId));
        if (dupCount != null && dupCount > 0) {
            throw new BizException("同一字典类型下键值已存在");
        }
    }

    private DictTypeVO toTypeVO(DictType t) {
        DictTypeVO vo = new DictTypeVO();
        vo.setId(t.getId());
        vo.setTypeCode(t.getTypeCode());
        vo.setTypeName(t.getTypeName());
        vo.setStatus(t.getStatus());
        vo.setRemark(t.getRemark());
        return vo;
    }

    private DictDataVO toDataVO(DictData d) {
        DictDataVO vo = new DictDataVO();
        vo.setId(d.getId());
        vo.setTypeCode(d.getTypeCode());
        vo.setDictLabel(d.getDictLabel());
        vo.setDictValue(d.getDictValue());
        vo.setSortNo(d.getSortNo());
        vo.setStyle(d.getStyle());
        vo.setIsDefault(d.getIsDefault());
        vo.setStatus(d.getStatus());
        vo.setRemark(d.getRemark());
        return vo;
    }
}
