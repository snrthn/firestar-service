package com.firestar.common;

import com.firestar.entity.Customer;
import com.firestar.entity.Project;
import com.firestar.entity.Receivable;
import com.firestar.mapper.CustomerMapper;
import com.firestar.mapper.ProjectMapper;
import com.firestar.mapper.ReceivableMapper;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 列表页批量预取工具：消灭 {@code toVO} 内逐条 selectById 的 N+1。
 *
 * <p>背景：列表页一页最多 500 条（各 Service 的 page 都按此封顶），若 toVO 里
 * 逐条 {@code selectById} 补显示字段，一页就会放大成 500 次单条查询。
 * 这里按页收集去重主键后走一次 {@code selectBatchIds}。</p>
 *
 * <p>V1 纪律：只用 BaseMapper 的 CRUD，零手写 SQL。</p>
 */
public final class BatchLoader {

    private BatchLoader() {
    }

    /**
     * 批量取项目（收款 / 发票 / 应收三个列表页共用）。
     *
     * @param rows    本页业务行（任意类型）
     * @param idGetter 从业务行取 projectId 的函数（如 {@code Receipt::getProjectId}）
     * @return projectId → Project；无 id 或空集时返回空 Map（不可变）
     */
    public static <R> Map<Long, Project> projects(ProjectMapper mapper, Collection<R> rows,
                                                  Function<R, Long> idGetter) {
        Set<Long> ids = distinctIds(rows, idGetter);
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return byId(mapper.selectBatchIds(ids), Project::getId);
    }

    /**
     * 批量取应收单（发票列表/详情要用 receivableNo 展示）。
     *
     * @param rows     本页业务行
     * @param idGetter 从业务行取 receivableId 的函数（如 {@code Invoice::getReceivableId}）
     * @return receivableId → Receivable；无 id 或空集时返回空 Map（不可变）
     */
    public static <R> Map<Long, Receivable> receivables(ReceivableMapper mapper, Collection<R> rows,
                                                        Function<R, Long> idGetter) {
        Set<Long> ids = distinctIds(rows, idGetter);
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return byId(mapper.selectBatchIds(ids), Receivable::getId);
    }

    private static <R> Set<Long> distinctIds(Collection<R> rows, Function<R, Long> idGetter) {
        return rows.stream()
                .map(idGetter)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private static <E> Map<Long, E> byId(List<E> list, Function<E, Long> idOf) {
        return list.stream().collect(Collectors.toMap(idOf, Function.identity()));
    }

    /**
     * 批量取客户名（V72 起 fin_project 不再存 customer_name，展示名需按 customer_id 联 fin_customer 翻译）。
     *
     * @param projects 已批量取回的 Project（含 customerId），去重后走一次 selectBatchIds
     * @return customerId → customerName；无 id 或空集时返回空 Map（不可变）
     */
    public static Map<Long, String> customerNames(CustomerMapper mapper, Collection<Project> projects) {
        Set<Long> ids = projects.stream()
                .map(Project::getCustomerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return mapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Customer::getId, Customer::getCustomerName));
    }
}
