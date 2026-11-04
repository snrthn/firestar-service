package com.firestar.config;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.springframework.stereotype.Component;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按「实体类 + id」泛型加载任意实体，供审计切面做 before 快照。
 *
 * <p>MyBatis-Plus 的所有 Mapper 都继承 {@code BaseMapper<Entity>}，启动时把所有 Mapper Bean 收集起来，
 * 解析其泛型实参得到 Entity 类型，建立 {@code Entity → Mapper} 映射。审计切面据此用一行
 * {@code selectById} 取到旧值，无需为每个实体写专用查询。
 *
 * <p>这是「可扩展」的关键：新增业务实体只要在 V{n} 里建表 + 建 Mapper（继承 BaseMapper），
 * 其 CRUD 方法标上 {@code @Auditable(entityClass = Xxx.class)} 即可自动获得 before 快照，零额外代码。
 */
@Component
public class GenericEntityLoader {

    private final Map<Class<?>, BaseMapper<?>> byEntity = new ConcurrentHashMap<>();

    public GenericEntityLoader(List<BaseMapper<?>> mappers) {
        for (BaseMapper<?> mapper : mappers) {
            for (Class<?> iface : mapper.getClass().getInterfaces()) {
                for (Type genericIface : iface.getGenericInterfaces()) {
                    if (genericIface instanceof ParameterizedType pt
                            && pt.getRawType() instanceof Class<?> raw
                            && BaseMapper.class.equals(raw)) {
                        Type entityType = pt.getActualTypeArguments()[0];
                        if (entityType instanceof Class<?> entityClass) {
                            byEntity.put(entityClass, mapper);
                        }
                    }
                }
            }
        }
    }

    /**
     * 按实体类 + id 加载记录。找不到对应 Mapper 或记录不存在返回 null（切面会跳过 before 快照）。
     */
    @SuppressWarnings("unchecked")
    public Object load(Class<?> entityClass, Long id) {
        BaseMapper<Object> mapper = (BaseMapper<Object>) byEntity.get(entityClass);
        if (mapper == null || id == null) {
            return null;
        }
        return mapper.selectById(id);
    }
}
