package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.common.MasterDataNoUtil;
import com.firestar.entity.Goods;
import com.firestar.mapper.GoodsMapper;
import com.firestar.service.GoodsService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.Map;

/** 货物主数据服务实现（逻辑对齐 CustomerServiceImpl.upsert）。 */
@Service
public class GoodsServiceImpl implements GoodsService {

    private final GoodsMapper goodsMapper;

    public GoodsServiceImpl(GoodsMapper goodsMapper) {
        this.goodsMapper = goodsMapper;
    }

    @Override
    public Long upsert(String name, Map<String, String> fields, boolean mergeEmpty, String source) {
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty()) throw new IllegalArgumentException("货物名称不能为空");
        Goods exists = goodsMapper.selectOne(new LambdaQueryWrapper<Goods>()
                .eq(Goods::getGoodsName, cleanName).last("LIMIT 1"));
        if (exists != null) {
            if (mergeEmpty) {
                Goods patch = new Goods();
                patch.setId(exists.getId());
                boolean changed = false;
                for (Map.Entry<String, String> e : fields.entrySet()) {
                    if (!StringUtils.hasText(e.getValue())) continue;
                    if (isBlankField(exists, e.getKey())) {
                        applyField(patch, e.getKey(), e.getValue().trim());
                        changed = true;
                    }
                }
                if (changed) goodsMapper.updateById(patch);
            }
            return exists.getId();
        }
        Goods created = new Goods();
        created.setGoodsName(cleanName);
        created.setStatus("ENABLED");
        created.setRemark(source == null ? "导入建档" : source);
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (StringUtils.hasText(e.getValue())) applyField(created, e.getKey(), e.getValue().trim());
        }
        goodsMapper.insert(created);
        created.setGoodsNo(MasterDataNoUtil.goodsNo(created.getId()));
        goodsMapper.updateById(created);
        return created.getId();
    }

    private static boolean isBlankField(Goods entity, String field) {
        Object v = readProp(entity, field);
        return v == null || (v instanceof String s && s.isEmpty());
    }

    private static void applyField(Goods entity, String field, String value) {
        String cap = Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            Method setter = Goods.class.getMethod("set" + cap, String.class);
            setter.invoke(entity, value);
        } catch (Exception ignored) {
            // 未知字段或非 String → 忽略
        }
    }

    private static Object readProp(Goods entity, String field) {
        String cap = Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            return Goods.class.getMethod("get" + cap).invoke(entity);
        } catch (Exception ignored) {
            return null;
        }
    }
}
