package com.firestar.ai.dto;

import java.util.List;

/**
 * 【模型配置】页「上移/下移」重排提交体（V215）。
 *
 * <p>提交的是<b>整表</b>的 id 顺序（按页面当前展示顺序从上到下），后端按
 * {@code (index + 1) * 10} 回写 sort_no —— 前端不参与算号，避免两边算法不一致
 * 导致「拖到 A 前面却跑到 A 后面」这类错序。</p>
 *
 * <p>后端会校验 ids 与库内行集合完全一致（数量 + 元素），不一致直接拒绝并要求
 * 刷新重试：多人同时维护配置时，用「部分 id」重排会把别人的新增行挤到未知位置。</p>
 */
public record ModelConfigSortRequest(List<Long> ids) {
}
