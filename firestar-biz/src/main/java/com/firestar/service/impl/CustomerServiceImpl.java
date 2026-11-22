package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.common.ImportContext;
import com.firestar.common.MasterDataNoUtil;
import com.firestar.dto.CustomerOptionVO;
import com.firestar.entity.Customer;
import com.firestar.mapper.CustomerMapper;
import com.firestar.service.CustomerService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * 客户主数据服务实现（见 {@link CustomerService}）。
 *
 * <p>逻辑与 {@code CustomerController#options} 完全一致：ENABLED + 名称模糊 + 名称升序 +
 * limit 夹紧（Integer 受 defaultValue 约束后再夹紧，防 SQL 注入/巨大结果集）。</p>
 */
@Service
public class CustomerServiceImpl implements CustomerService {

    private final CustomerMapper customerMapper;

    public CustomerServiceImpl(CustomerMapper customerMapper) {
        this.customerMapper = customerMapper;
    }

    @Override
    public List<CustomerOptionVO> search(String keyword, Integer limit) {
        LambdaQueryWrapper<Customer> q = new LambdaQueryWrapper<Customer>()
                .eq(Customer::getStatus, "ENABLED")
                .orderByAsc(Customer::getCustomerName);
        if (StringUtils.hasText(keyword)) {
            q.like(Customer::getCustomerName, keyword.trim());
        }
        int safeLimit = (limit == null || limit <= 0) ? 200 : Math.min(limit, 500);
        q.last("LIMIT " + safeLimit);

        return customerMapper.selectList(q).stream().map(c -> {
            CustomerOptionVO vo = new CustomerOptionVO();
            vo.setId(c.getId());
            vo.setCustomerNo(c.getCustomerNo());
            vo.setCustomerName(c.getCustomerName());
            vo.setContactPhone(c.getContactPhone());
            return vo;
        }).toList();
    }

    @Override
    public Long upsert(String name, Map<String, String> fields, boolean mergeEmpty, String source) {
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty()) {
            throw new IllegalArgumentException("客户名称不能为空");
        }
        Customer exists = customerMapper.selectOne(new LambdaQueryWrapper<Customer>()
                .eq(Customer::getCustomerName, cleanName).last("LIMIT 1"));
        if (exists != null) {
            if (mergeEmpty) {
                Customer patch = new Customer();
                patch.setId(exists.getId());
                boolean changed = false;
                for (Map.Entry<String, String> e : fields.entrySet()) {
                    if (!StringUtils.hasText(e.getValue())) continue; // 空值跳过，不覆盖
                    if (isBlankField(exists, e.getKey())) {
                        applyField(patch, e.getKey(), e.getValue().trim());
                        changed = true;
                    }
                }
                if (changed) customerMapper.updateById(patch);
            }
            return exists.getId();
        }
        Customer created = new Customer();
        created.setCustomerName(cleanName);
        created.setCreatedBy(ImportContext.getOperatorId());
        created.setStatus("ENABLED");
        created.setRemark(source == null ? "导入建档" : source);
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (StringUtils.hasText(e.getValue())) applyField(created, e.getKey(), e.getValue().trim());
        }
        customerMapper.insert(created);
        created.setCustomerNo(MasterDataNoUtil.customerNo(created.getId()));
        customerMapper.updateById(created);
        return created.getId();
    }

    /** 反射判断实体某字段当前是否为 null / 空串（merge 空字段用） */
    private static boolean isBlankField(Customer entity, String field) {
        Object v = readProp(entity, field);
        return v == null || (v instanceof String s && s.isEmpty());
    }

    /** 反射写字段（只写 Customer 真实存在的 String 属性，未知字段忽略） */
    private static void applyField(Customer entity, String field, String value) {
        String cap = Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            Method setter = Customer.class.getMethod("set" + cap, String.class);
            setter.invoke(entity, value);
        } catch (Exception ignored) {
            // 字段不存在或非 String 类型 → 忽略（如 status 由固定逻辑控制）
        }
    }

    private static Object readProp(Customer entity, String field) {
        String cap = Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            return Customer.class.getMethod("get" + cap).invoke(entity);
        } catch (Exception ignored) {
            return null;
        }
    }
}
