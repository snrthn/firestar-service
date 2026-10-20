# P2 通知 MVP — 实施方案

> 依据：调研结论文档 10.9（分期与验收）/ 10.5（通知模块收窄版）/ 3.1~3.5（分层与数据模型）。
> 范围决策（虎哥 2026-10-20 拍板）：**按方案 C 执行** —— 只做通知中枢（表 + dedup + 落库 + SSE 推送 + 铃铛），**事件源留接口占位**，用管理端「手动发布」触发验收。审批/回款/开票等业务事件源等对应业务落地后直接接。
> 依赖：P0 SSE 通道已就绪（`RealtimeEventType.NOTIFY` 枚举 + 前端 `realtimeDispatch` 的 `NOTIFY` case 均已预留，本阶段是"填空"不是"新建"）。

---

## 一、版本号：自动累加 → V30

**规则：不再手写文档编号，一律取 `src/main/resources/db/migration` 下现有最大版本号 +1。**
现状最大为 `V29__rename_open_source_notice_to_about_us.sql` → **P2 迁移编号 = `V30`**。

> ⚠️ 文档 10.9 里写的「V26（2 张表）」是旧编号，V26 已被 `V26__fix_menu_id25_conflict.sql` 占用且已进库，**禁止复用**。
> 开工前再跑一次确认（防并行改动）：
> ```powershell
> Get-ChildItem 'D:\firestar-project\firestar-service\src\main\resources\db\migration' -Filter V*.sql |
>   ForEach-Object { [int]($_.Name -replace '^V(\d+)__.*$','$1') } | Measure-Object -Maximum
> ```

---

## 二、数据模型与迁移（V30）

### 2.1 两张表（DDL 草稿）

```sql
-- 通知事实表：一人一条，便于独立已读；只插入 + 更新 read_at，不删
CREATE TABLE IF NOT EXISTS `sys_notification` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `tenant_id`    BIGINT       NOT NULL DEFAULT 0,
  `version`      INT          NOT NULL DEFAULT 0,   -- 乐观锁（BaseEntity）
  `receiver_id`  BIGINT       NOT NULL,
  `type`         VARCHAR(32)  NOT NULL,             -- APPROVAL_TODO / SYSTEM_NOTICE ...
  `title`        VARCHAR(128) NOT NULL,
  `content`      VARCHAR(1024) NOT NULL DEFAULT '',
  `biz_type`     VARCHAR(32)  NULL,
  `biz_id`       VARCHAR(64)  NULL,
  `link_url`     VARCHAR(255) NULL,                 -- 点击跳转（相对路径）
  `level`        VARCHAR(16)  NOT NULL DEFAULT 'INFO', -- INFO / WARN / URGENT
  `read_at`      DATETIME     NULL,
  `read_channel` VARCHAR(16)  NULL,                 -- INAPP
  `dedup_key`    VARCHAR(128) NOT NULL,             -- 唯一键：重复触发不落第二条
  `expire_at`    DATETIME     NULL,
  `ext`          JSON         NULL,                 -- ⚠️ BaseEntity 全字段铁律（V27 血案）
  `created_by`   VARCHAR(64)  NULL,
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_by`   VARCHAR(64)  NULL,
  `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dedup` (`dedup_key`),
  KEY `idx_receiver_read_created` (`receiver_id`, `read_at`, `created_at`),
  KEY `idx_receiver_type_created` (`receiver_id`, `type`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知事实表';

-- 通知模板：管理员可改文案；⚠️ 不设 version 字符串列（与 BaseEntity 乐观锁 version 冲突，V25 血案）
CREATE TABLE IF NOT EXISTS `sys_notification_template` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `tenant_id`   BIGINT       NOT NULL DEFAULT 0,
  `version`     INT          NOT NULL DEFAULT 0,   -- 乐观锁（BaseEntity）
  `code`        VARCHAR(32)  NOT NULL,             -- APPROVAL_TODO / RECEIPT_AUDIT ...
  `type`        VARCHAR(32)  NOT NULL,
  `title_tpl`   VARCHAR(255) NOT NULL,
  `content_tpl` TEXT         NOT NULL,             -- {{变量}} 占位
  `enabled`     TINYINT      NOT NULL DEFAULT 1,
  `ext`         JSON         NULL,                 -- ⚠️ ext 铁律
  `created_by`  VARCHAR(64)  NULL,
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_by`  VARCHAR(64)  NULL,
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tpl_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知模板表';
```

### 2.2 种子模板（对应 3.3 七类，文案占位、后续可改）

`APPROVAL_TODO` / `APPROVAL_RESULT` / `RECEIPT_AUDIT` / `INVOICE_DONE` / `BUDGET_ALERT` / `LOAN_OVERDUE` / `SYSTEM_NOTICE`，`enabled=1`。
P2 阶段只有 `SYSTEM_NOTICE` 真正被管理端发布用到；其余先种着，等业务接入即生效。

### 2.3 菜单与权限种子（⚠️ id 必须查库后定）

系统管理下两个子菜单：**通知模板**（`route_path=/system/notify-template`、`component=system/notify-template`、`perm_code=notify:template`）、**通知记录**（`route_path=/system/notify-record`、`component=system/notify-record`、`perm_code=notify:manage`）。
**写种子前必须执行** `SELECT id, menu_name, parent_id, sort_no FROM sys_menu ORDER BY sort_no` 全量核对已占用 id 与 sort_no，取空位/最大值+1（V25/V26 血案铁律）。

### 2.4 幂等与同步项

- 照 V27 范式：procedure + `information_schema` 守卫做幂等（手动 source 过再被 Flyway 跑也不报错）。
- `DatabaseCheckService.EXPECTED_TABLE_COUNT`：31 → **33**（P1 时同步过一次，同一处）。
- 全局通知开关复用 P1 已种的 `sys_config.notify_global_switch`，**不新增** key。

---

## 三、后端实现

### 3.1 文件清单

| 层 | 文件 |
|---|---|
| entity | `Notification.java` / `NotificationTemplate.java`（`@TableName(autoResultMap = true)`，含 JSON 列的实体必须标注） |
| mapper | `NotificationMapper.java` / `NotificationTemplateMapper.java` |
| dto | `NotificationVO` / `PublishNotificationRequest` / `TemplateSaveRequest` / `TemplateVO` |
| service | `NotificationService` + `NotificationServiceImpl` |
| controller | `NotificationController`（用户侧）/ `AdminNotificationController`（管理侧） |

### 3.2 NotificationService（中枢，唯一入口）

```java
/** 业务侧唯一调用点。业务代码不认识 SSE、不建表、不写推送。 */
public void notify(NotifyCommand cmd);
```

内部链路（照 3.1 分层）：
1. **全局开关**：`notify_global_switch=false` → 直接 return（不落库）。
2. **模板渲染**：按 `cmd.code` 取模板 → `{{变量}}` 替换（缺失变量留空并 warn，不抛）。
3. **收件人解析**：`cmd.receivers`（userId 列表；空 = 不发，**广播由调用方显式给全员 id 列表**，避免误广播）。
4. **dedup 落库**：`dedup_key` 由 cmd 提供或按规则拼 `{type}:{bizType}:{bizId}:{receiverId}`；用 **`INSERT IGNORE` + 唯一键**保证并发/重试不落重复（先查后插有竞态）。
5. **发事件**：`applicationEventPublisher.publishEvent(new RealtimeEvent(username, NOTIFY, {unreadCount, latest:{id,type,title,ts}}))` → `SseRealtimeGateway` 已实现路由，无需改网关。
6. **unreadCount**：落库后 `SELECT COUNT(*) WHERE receiver_id=? AND read_at IS NULL` 进载荷（推送不塞全文，前端点开面板再拉列表校正）。

### 3.3 接口

**用户侧（登录即可，无权限点）**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/notifications/unread-count` | `{count}`；前端启动/重连后校正用 |
| GET | `/notifications?lastId=&size=` | 游标分页，默认 size=10，最大 50 |
| POST | `/notifications/{id}/read` | 置已读（写 `read_at`/`read_channel=INAPP`），幂等 |
| POST | `/notifications/read-all` | 全部已读 |

**管理侧**

| 方法 | 路径 | 权限点 | 说明 |
|---|---|---|---|
| POST | `/admin/notifications/publish` | `notify:manage` | **手动发布（P2 验收触发器）**：type/code、标题、正文、linkUrl、level、receivers（userId 列表） |
| GET | `/admin/notifications` | `notify:manage` | 记录查询：按 type / 时间区间 / 收件人检索 |
| GET | `/admin/notification-templates` | `notify:template` | 模板列表 |
| PUT | `/admin/notification-templates/{id}` | `notify:template` | 改文案 / 开关（改后新通知用新文案，历史不改写） |

---

## 四、前端实现

### 4.1 铃铛（header）
- 位置：`AppHeader.vue` header 右侧，全屏按钮旁（个人中心头像左边）。
- 形态：铃铛图标 + 数字角标（99+ 封顶）+ 下拉面板。
- 面板：**不分 Tab**（仅站内）；列表 10 条 + 滚动加载到 50 条 + 「已加载全部」；底部「全部标为已读」；**不做独立列表页**（10.5 收窄决定）。
- **已读时机（固定规则）**：点击某条 → 先 `POST /{id}/read` 成功 → 再跳 `link_url`。**列表滚动不产生已读。**
- 桌面通知（Notification API）：**不做**（10.5）。

### 4.2 SSE 接线（填空）
- `src/utils/realtimeDispatch.ts` 的 `NOTIFY` case：把 `console.info` 占位替换为铃铛 store 更新（`unreadCount` 直接用推送值）。
- 铃铛状态放轻量 store（或 composable）：`unreadCount` + `items` + `panelOpen`；断线重连后调一次 `/notifications/unread-count` 校正（**未读数以事实表为根，断网不丢**）。
- `realtimeClient` 重连已有机制，无需改动。

### 4.3 管理页（系统管理下）
- `views/system/notify-template.vue`：模板表格 + 编辑文案弹层（`notify:template`）。
- `views/system/notify-record.vue`：记录表格（检索）+ **「发布通知」按钮**（弹层：类型/标题/正文/链接/级别/接收人多选 → 调 publish）。**这就是 C 方案的验收操作入口。**

### 4.4 路由归属决策（虎哥 2026-10-20 追问拍板）

通知**用户侧与管理侧走两条不同的路**，不是二选一：

| 层 | 挂法 | 依据 |
|---|---|---|
| 用户侧（铃铛 / 面板 / 将来的「全部通知」页） | **`builtinPages` 本地常量路由**（同 `/profile` 模式），header 组件不依赖菜单 | ① 通知是**全登录用户功能**——每人只看自己的，系统管理菜单的语义是"管理员配置系统"，普通角色塞进去语义不符；② sys_menu 菜单是「授权 ∧ 启用」才注册路由，不给某角色授权 → 该用户 SSE 推得到（铃铛在 header，与菜单无关）却点不开列表，**读写割裂**；③ `/profile` 就是同型先例（header 用户下拉入口，sys_menu 无行，任何登录态可用，router/index.ts:59-61 实锤） |
| 管理侧（通知模板 / 通知记录） | **sys_menu 系统管理子菜单**（见 2.3 节），RBAC 授权 | 管理员功能，`notify:template` / `notify:manage` 权限点，未授权角色侧栏不可见、URL 直达落 403——这正是要的行为 |

当前 P2 范围内用户侧**零新增路由**（铃铛 + 下拉面板即终点，不做独立页——10.5 收窄）；若 P3 将来要做「全部通知」独立页，直接进 `builtinPages`，**不要**建 sys_menu 行。

---

## 五、实施步骤（建议顺序）

1. **查库核对**：菜单 id / sort_no 占用现状 → 定两个新菜单的 id 与 sort_no。
2. **V30 迁移**（2 表 + 7 条模板种子 + 菜单权限种子 + DatabaseCheckService 计数同步）；写完**本地库实跑**（V25 的教训：迁移 SQL 不进编译，不实跑等于没验）。
3. 后端 entity / mapper。
4. `NotificationService`（开关 → 渲染 → 收件人 → dedup 落库 → 事件）。
5. 用户侧 Controller（4 个接口）+ 管理侧 Controller（5 个接口）。
6. 后端编译 + 冒烟（起 3722 临时端口，验完主动退服）。
7. 前端铃铛 + dispatch 接线 + store。
8. 前端管理页两个。
9. 端到端验收（见第六节）。
10. 文档更新（10.9 分期表 P2 标记完成 + 实施记录）。

---

## 六、验收清单（对应 10.9 的 P2 三项 + C 方案手动触发）

> 实施记录（2026-10-20，3722 临时端口实测，验完主动退服、端口已确认释放）：
> **后端 API 层全部实测通过**；前端铃铛/面板/管理页已完成并通过 vue-tsc + vite build，
> **UI 层交互待虎哥浏览器验收**（登录后看铃铛角标、点开面板、管理系统下两个新菜单）。

- [x] **管理端发布一条通知**（`/admin/notifications/publish`）→ 实测 inserted=1、未读数 +1、列表出现该条。（前端 `/system/notify-record` 页已建，UI 待验收）
- [x] **SSE 推送**：账号保持登录态，管理端再发一条 → 实测**不刷新即收到 `event:NOTIFY` 帧**（`{"unreadCount":2,"latest":{"id":9,...}}`），证明走 SSE 加速层而非轮询。
- [x] **断网重连未读数不丢**：API 层实测——SSE 连接断开期间 publish 照样落库（事实表 4 行），未读数接口始终 = 事实表 COUNT；前端重连校正逻辑已接（store.init/refreshUnread），UI 层待联调。
- [x] **dedup 不重**：SQL 层实测——同一 `dedup_key` 两次 `INSERT IGNORE` 最终只有 1 行；普通 INSERT 撞唯一键报 1062（`uk_dedup` 生效实锤）。
- [x] **已读规则**：API 层实测——单条已读幂等（已读再点不报错）、全部已读返回 updated 条数、角标清零；前端"点击先置已读再跳 link_url、滚动不产生已读"已按规则实现，UI 待验收。
- [x] **模板可改**：实测改 `SYSTEM_NOTICE` 文案 → 发布一条（带 code + vars）→ 新通知 title = `【测试】虎哥您好：模板渲染 OK`（`{{who}}`/`{{title}}` 均正确替换）；验证后**已恢复模板原文案**、测试通知已清空。
- [x] **全局开关**：`notify_global_switch=false` → publish 返回 **400 + "全局通知开关已关闭，不能发布"**（业务拒绝转友好文案，不落通用 500）；改回 true 恢复。
- [x] **P0 不回归**：`/sse/ping` 探针实测 200 + `event:PONG`；**单会话互踢意外实测命中**——验收中途本机 Chrome 用 admin 重新登录，旧 token 立即 401（V15 机制按设计工作，反证 SSE/通知改动没有破坏认证链路）。
- [ ] **单据/链接失效兜底**：发一条 `link_url` 指向不存在路径的通知 → 点击后面板有提示而非白屏 404。（前端行为，UI 验收时一并过）

---

## 七、待确认决策点

1. **权限点归属**：`notify:template` / `notify:manage` 两个点是否也要给 ROLE_ADMIN 授权种子（P1 的 V25 做法是显式授权，建议照做）。→ **已照做**（V30 第 6 节，profile 接口实测 permissions 含两点）。
2. **管理端发布是否允许选角色/全员**：V2 阶段建议**只支持按用户 ID 列表**（避免误广播全员）；全员发布等业务事件源成熟后再评估。→ **已按此实现**（receivers 为 userId 列表，空则拒绝）。
3. **通知保留期**：`expire_at` 字段已留，是否 P2 就加定时清理任务？建议**留字段、不加任务**（P3 再定保留策略）。→ **已按此实现**（字段在、无清理任务）。

---

## 八、实施记录（2026-10-20）

**改动文件**

| 层 | 文件 |
|---|---|
| 迁移 | `V30__p2_notification_module.sql`（2 表 + 7 模板种子 + 2 权限点 + 菜单 id=32/33 + ROLE_ADMIN 授权；本地库实跑通过，旧菜单/旧权限点 diff=0） |
| 常量 | `DatabaseCheckService.EXPECTED_TABLE_COUNT` 31→33（同步注释） |
| entity | `Notification.java` / `NotificationTemplate.java` |
| mapper | `NotificationMapper.java`（含 `insertIgnore` 自定义方法）/ `NotificationTemplateMapper.java` |
| dto | `NotifyCommand` / `PublishNotificationRequest` / `NotificationVO` / `TemplateVO` / `TemplateSaveRequest` |
| service | `NotificationService` + `NotificationServiceImpl`（开关→渲染→收件人→dedup 落库→SSE 事件） |
| controller | `NotificationController`（用户侧 4 接口，无权限点）/ `AdminNotificationController`（管理侧 4 接口，业务拒绝转 400） |
| 前端 | `types/notification.ts` / `api/notification.ts` / `stores/notify.ts` / `utils/realtimeDispatch.ts`（NOTIFY case 接 store）/ `AppHeader.vue`（铃铛+角标+面板）/ `views/system/notify-template.vue` / `views/system/notify-record.vue` |

**验证**：mvn compile EXIT=0；vue-tsc EXIT=0；vite build EXIT=0（11.24s）；3722 端口端到端见第六节（发布/SSE/dedup/已读/模板/开关/探针全过）。

**踩坑记录**
1. Vue mustache 嵌套：`{{ '{{变量}}' }}` 让插值解析器提前闭合报 "Unterminated string constant" → 静态文案用 HTML 实体 `&#123;&#123;` 绕过。
2. 后端起 3722 首次失败：默认 profile 是 prod（jwt.secret 无回退）→ 必须显式 `SPRING_PROFILES_ACTIVE=dev`。
3. 验收中途旧 token 集体 401：本机 Chrome 用 admin 重新登录触发单会话互踢（V15 设计行为），非通知模块问题。
4. PowerShell `ConvertTo-Json` 序列化 hashtable 偶发把字符串字段变成 JSON 对象 → 手拼 JSON 字符串更稳。
