package com.firestar.service;

import com.firestar.dto.CustomerOptionVO;

import java.util.List;

/**
 * 客户主数据服务（AI 工具 search_customers 的支撑）。
 *
 * <p>客户在 V68 一期只有主表 + 历史回填 + 项目表单关联下拉，没有独立管理页面，
 * 也没有 CustomerService（各业务 Service 直接注入 {@code CustomerMapper} 做名称装载）。
 * 本接口把「客户搜索」从 {@code CustomerController#options} 的裸 Mapper 逻辑收敛为
 * 可复用、可单测的 Service，供 AI 工具调用；与 /customer/options 同口径：
 * 仅 ENABLED、按名称模糊、名称升序、limit 夹紧。</p>
 *
 * <p>返回瘦 VO（id/编号/名称/电话），不含税号/银行账号——与 {@code CustomerOptionVO}
 * 设计一致（避免把敏感字段推到 AI 上下文）。</p>
 */
public interface CustomerService {

    /**
     * 按客户名称模糊搜索客户主数据。
     *
     * @param keyword 客户名称关键词（可空，空即返回全部 ENABLED）
     * @param limit   返回条数上限（可空，默认 200，最多 500，防异常数据撑爆）
     * @return 客户瘦 VO 列表
     */
    List<CustomerOptionVO> search(String keyword, Integer limit);
}
