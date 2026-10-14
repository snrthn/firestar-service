package com.firestar.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.dto.DictDataRequest;
import com.firestar.dto.DictDataVO;
import com.firestar.dto.DictTypeRequest;
import com.firestar.dto.DictTypeVO;
import com.firestar.service.DictService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 字典管理接口（前端 /api/system/dict-type/** 与 /api/system/dict-data/**，
 * 代理剥离 /api 后到 /system/**）。
 *
 * 【路径约定】同 MenuController：后端路由不带 /api；除 /auth/login 外默认需 JWT，
 * SecurityConfig 白名单未放行本路径，天然受保护。
 *
 * 【页面分工】dict-type 对应主页面 system/dict.vue（字典类型）；
 * dict-data 对应隐藏二级页 system/dict-data.vue（按类型编码查字典数据），
 * 该页在菜单表中 visible=0，侧栏不渲染但路由可访问。
 */
@RestController
@RequirePerm("dict:manage")
@RequestMapping("/system/dict")
public class DictController {

    private final DictService dictService;

    public DictController(DictService dictService) {
        this.dictService = dictService;
    }

    // ===== 字典类型 =====

    /**
     * 字典类型分页列表（默认每页 10 条，与前端 el-pagination 默认规格一致）。
     * pageNum/pageSize 非法值由 Service 层兜底（1 / 10，size 上限 500）。
     */
    @GetMapping("/type/list")
    public Result<IPage<DictTypeVO>> listTypes(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(dictService.listTypes(pageNum, pageSize));
    }

    /**
     * 全量类型列表（不分页）。供隐藏二级页按 typeCode 反查类型名称，
     * 管理页请走 /type/list 分页接口。
     */
    @GetMapping("/type/all")
    public Result<List<DictTypeVO>> listAllTypes() {
        return Result.success(dictService.listAllTypes());
    }

    @PostMapping("/type")
    public Result<Long> createType(@RequestBody DictTypeRequest req) {
        return Result.success(dictService.createType(req));
    }

    @PutMapping("/type/{id}")
    public Result<Void> updateType(@PathVariable Long id, @RequestBody DictTypeRequest req) {
        dictService.updateType(id, req);
        return Result.success();
    }

    @DeleteMapping("/type/{id}")
    public Result<Void> deleteType(@PathVariable Long id) {
        dictService.deleteType(id);
        return Result.success();
    }

    // ===== 字典数据 =====

    /**
     * 字典数据分页列表（默认每页 10 条，与前端 el-pagination 默认规格一致）。
     * pageNum/pageSize 非法值由 Service 层兜底（1 / 10，size 上限 500）。
     */
    @GetMapping("/data/list")
    public Result<IPage<DictDataVO>> listData(
            @RequestParam String typeCode,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(dictService.listData(typeCode, pageNum, pageSize));
    }

    @PostMapping("/data")
    public Result<Long> createData(@RequestBody DictDataRequest req) {
        return Result.success(dictService.createData(req));
    }

    @PutMapping("/data/{id}")
    public Result<Void> updateData(@PathVariable Long id, @RequestBody DictDataRequest req) {
        dictService.updateData(id, req);
        return Result.success();
    }

    @DeleteMapping("/data/{id}")
    public Result<Void> deleteData(@PathVariable Long id) {
        dictService.deleteData(id);
        return Result.success();
    }
}
