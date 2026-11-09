package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.dto.CustomerOptionVO;
import com.firestar.entity.Customer;
import com.firestar.mapper.CustomerMapper;
import com.firestar.service.CustomerService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

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
}
