package com.firestar.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.dto.DictDataRequest;
import com.firestar.dto.DictDataVO;
import com.firestar.dto.DictTypeRequest;
import com.firestar.dto.DictTypeVO;

import java.util.List;

/**
 * 字典管理服务（字典类型 + 字典数据，同域合体，对应前端
 * system/dict.vue（类型）与 system/dict-data.vue（隐藏二级页，数据））。
 */
public interface DictService {

    // ===== 字典类型 =====

    /**
     * 分页查字典类型列表。typeName/typeCode 模糊、status 精确，三者均可选（为空则不参与过滤，
     * 默认仍能看到停用项，符合管理页要能查到停用项的需求）；按 id 升序。
     * pageNum/pageSize 为空或非法时按默认值 1 / 10 兜底；size 上限 500。
     */
    IPage<DictTypeVO> listTypes(Integer pageNum, Integer pageSize, String typeName, String typeCode, String status);

    /**
     * 全量类型列表（不分页）。供隐藏二级页 dict-data.vue 反查类型名称用——
     * 该页只需要一个 typeCode 对应的名称，不适合跟着管理页的分页翻页。
     */
    List<DictTypeVO> listAllTypes();

    Long createType(DictTypeRequest req);

    /** 修改不允许变更 typeCode（防存量数据孤儿），服务层以库中现存值为准 */
    void updateType(Long id, DictTypeRequest req);

    /** 删除前校验：类型下存在字典数据则拒删 */
    void deleteType(Long id);

    // ===== 字典数据 =====

    /**
     * 按类型编码分页查字典数据（按 sort_no、id 升序）。
     * pageNum/pageSize 为空或非法时按默认值 1 / 10 兜底；size 上限与
     * MybatisPlusConfig.MAX_PAGE_SIZE 对齐（500），超出截断。
     */
    IPage<DictDataVO> listData(String typeCode, Integer pageNum, Integer pageSize);

    Long createData(DictDataRequest req);

    /** 修改同样不允许变更所属 typeCode */
    void updateData(Long id, DictDataRequest req);

    void deleteData(Long id);
}
