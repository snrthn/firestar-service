package com.firestar.ai.vo;

import lombok.Getter;
import lombok.Setter;

/**
 * 移动节点请求（对齐前端 moveKbNode 的 payload）。
 *
 * <p>新契约（步骤 4.6）：对齐飞书「前端不碰排序号」+ Confluence 相对位置语义，
 * 前端只描述「相对谁、放在哪」，由服务端在事务内重排同父兄弟的 sort_no：</p>
 *
 * <pre>
 *   BEFORE / AFTER : targetId = 目标兄弟节点 id；移动后成为它的同级，排在它前 / 后
 *   APPEND         : targetId = 目标父节点 id（0 = 根目录）；追加到该父的末尾
 * </pre>
 *
 * <p>{@code parentId + sortNo} 保留为兼容存量客户端的兜底通道（position 为空时生效）。
 * 新代码一律不要再用：前端计算绝对排序号正是拖拽顺序漂移的根因（设计文档 §21）。</p>
 */
@Getter
@Setter
public class KbNodeMoveReq {

    /** BEFORE / AFTER / APPEND；为空则回落到旧契约 parentId + sortNo */
    private String position;
    /** BEFORE/AFTER 时为兄弟节点 id；APPEND 时为目标父 id（0 = 根） */
    private Long targetId;

    /** @deprecated 旧契约，仅兼容保留 */
    private Long parentId;
    /** @deprecated 旧契约，仅兼容保留 */
    private Integer sortNo;
}
