# firestar-service

火星财务 —— 后端服务仓库（`firestar-` = backend）。

与前端 [`firestar-manager`](../firestar-manager) 一一对应；前端为 Vue 3 + Vite 管理后台。

> ⚠️ **命名约定（务必分清）**：本 **git 仓库**名为 `firestar-service`；仓库内部是一个
> **Maven 多模块 reactor**，其中唯一可执行的 **应用模块**名为 `firestar-server`
> （产物 `firestar-server-0.0.1-SNAPSHOT.jar`）。改脚本 / CI / 文档时别把两者混为一谈。
> 这是**模块解耦（monorepo），不是微服务**：单一父工程、单一可执行 jar、同 JVM classpath 调用、
> 共用一个库与一套 Flyway。

## 技术栈（已确定）

> 对齐《火星财务系统技术栈文档》与参考项目 be-media-man-system 的工程结构。

- **Spring Boot 3.3.x** + **Java 17**（jakarta 命名空间）
- **Flowable 7.1** —— 审批引擎
- **MyBatis-Plus 3.5** —— ORM（Spring Boot 3 专用 starter）
- **Spring Security 6** —— 安全框架（JWT + RBAC 双线权限模型，已落地）
- **Flyway 10** —— 数据库迁移（core / biz 双 location）
- **SpringDoc 2.6** —— 接口文档（OpenAPI3）
- **MySQL 8** / **Redis 7** —— 主库 / 缓存（技术栈共享基础设施）
- **Hutool 5.8** —— 工具库

> 参考项目 be-media-man-system 实际跑的是 Spring Boot 2.7.18；本仓库按技术栈文档升级到 3.3.x。

## 工程结构（截至 2026-11-22 实际状态）

> ⚠️ 历史 README 此处画的是单模块 `src/main/java/com/firestar/`，与代码严重不符；
> 2026-11-04 已完成内核/业务分离，以下为重新盘点后的真实结构。

```
firestar-service/                       # git 仓库根，同时是 Maven reactor 根
├── pom.xml                             # 父工程 firestar-parent（packaging=pom，modules 三个）
├── firestar-core/                      # 平台内核：349 个 .java（含 ai/、ai/kb 包）
│   └── src/main/
│       ├── java/com/firestar/          # 用户/角色/字典/菜单/权限/租户/AI/知识库/工作流/组织/日志/通知/认证/文件/初始化重置
│       └── resources/db/migration/core/   # 83 个平台侧 Flyway 迁移（含 V200+ 知识库/登录防爆破系列）
├── firestar-biz/                       # 火星业务：119 个 .java
│   └── src/main/
│       ├── java/com/firestar/          # 项目/发货/退货/应收/发票/收款/核销/客户/物品/协议/导入 + 业务工具
│       └── resources/db/migration/biz/    # 44 个业务侧 Flyway 迁移（含 V200 起新增，V219 导入权限）
├── firestar-server/                    # 应用入口：1 个 .java（FirestarApplication）
│   ├── pom.xml                         # 聚合 core + biz，产出唯一可执行 jar
│   └── src/
│       ├── main/resources/             # application.properties + application-{dev,test,pre,prod}.properties
│       └── test/java/com/firestar/     # 23 个测试类（绝大多数 @SpringBootTest 集成测试需可达 it MySQL；standalone MockMvc 切片测试不连库）
├── bin/                                # start.sh / stop.sh / deploy.sh（跨平台运维脚本）
├── docs/                               # 开发规范.md / 底座化指南.md / 云效率流水线配置.yaml / git-workflow.md
├── scripts/                            # check_module_boundaries.py（CI 守边界）/ reset_flyway.sql（dev 清历史）
└── logs/ uploads/                      # 运行期产物（不入库）
```

模块依赖方向（单向，不可倒挂）：`firestar-server → firestar-biz → firestar-core`。
`firestar-core` **不得 import 任何只在 `firestar-biz` 定义的类**，由
`scripts/check_module_boundaries.py` 守护（建议接进 CI 或提交前执行）。

## 数据库迁移（Flyway）

V1–V108 保留原版本号，按归属拆到两个目录，四个 profile 统一配置：

```properties
spring.flyway.locations=classpath:db/migration/core,classpath:db/migration/biz
```

- `firestar-core/src/main/resources/db/migration/core`（83 个，含 V200+ 登录防爆破 / 知识库系列）
- `firestar-biz/src/main/resources/db/migration/biz`（44 个，含 V200 起新增，V219 导入权限）
- Flyway 按版本号全局排序，与所在目录无关；新增业务迁移用 **V200+**，避免与历史版本冲突。
- `scripts/reset_flyway.sql` 仅 dev/test 用于清 `flyway_schema_history` 重跑全量，**prod 严禁**。
- ⚠️ **迁移脚本禁止硬编码库名**：`information_schema` 探测一律用 `DATABASE()`。历史 `V27` / `V28` / `V75` 写死了 `table_schema = 'firestar'`，导致任何库名不叫 `firestar` 的新环境从空库初始化必然挂在 V75。已应用迁移不可改（checksum 铁律），**新迁移一律用 `DATABASE()`**。

## 集成测试（it profile）

集成测试**只连 `firestar_test`，绝不写 dev 库 `firestar`**：

```bash
bash bin/test.sh                                # 日常一键：重建空库 → Flyway 重放 → 全量 mvn test
bash bin/test.sh -Dtest=SmokeTest               # 只跑指定测试类（透传 Maven 参数）
bash bin/test.sh --no-reset -Dtest=RbacCrudTest # 沿用现有库，快速重跑
bash bin/test.sh --help                         # 帮助
```

入口 `bin/test.sh` 是薄门面，实现在 `scripts/run-integration-tests.sh`。

⚠️ **不要裸敲 `mvn test`**：干净 shell 缺 `DB_PASSWORD`（真实凭据只允许来自 `.env/dev.env`，不进版本库，空密码连库会 `Access denied`）；且裸跑不会重建库，上轮残留数据可能让用例撞唯一键或种子计数漂移。脚本会自动注入凭据、重建空库、清理 `SPRING_PROFILES_ACTIVE`。
`SPRING_PROFILES_ACTIVE` / JWT / AI_KEK / Coze 密钥 / OSS 凭据**均已无需手动注入**（15 个 `@SpringBootTest` 统一 `@ActiveProfiles("it")`，测试专用回退值都在 `application-it.properties`）。

- 配置：`firestar-server/src/test/resources/application-it.properties`，只在 test classpath、不进生产 jar；15 个 `@SpringBootTest` 统一 `@ActiveProfiles("it")`。
- schema + 种子由 Flyway 从空库全量重放自建（109 张表，与 dev 库一致），无需手工导 dump。
- 3 个测试专用补偿迁移（`src/test/resources/db/migration/it/V26.1` / `V27.1` / `V74.1`）补的是历史脚本硬编码库名跳过的 DDL，只在 it 的 `spring.flyway.locations` 里加载，生产四个 profile 零影响。
- 期望值单一来源：`DatabaseCheckService` 的表数 / 种子数常量为 public，测试直接引用；`EntityMappingConsistencyTest` 的表清单按 `BUSINESS_TABLE_PREFIXES` 从 JDBC 元数据动态取，不再维护第二份白名单。
- 云效构建容器无 MySQL，CI 仍只跑纯单测（`-Dtest` 白名单 8 类：`SmokeTest` / `AuthControllerStreamTest` / `AuditDictConsistencyTest` / `DatabaseCheckSeedBaselineGuardTest` / `GlobalExceptionHandlerTest` / `LoginRateLimitFilterTest` / `InitStatusExposureTest` / `InitResetChallengeTest`）；集成测试在带 MySQL 的本地 / 自建 CI 环境跑。其中 `AuditDictConsistencyTest` 静态扫描 `@Auditable` 注解 ↔ `sys_dict_data` 字典的双向差集，枚举漏补字典即 fail，不连库。
- `scripts/reset_test_db.sql` 只 DROP/CREATE `firestar_test`，**prod 严禁**。

## 当前进度（截至 2026-11-22）

- **数据层 / 基础设施：已就绪** —— 业务表 DDL 全落地、实体 / Mapper 与库严格对齐、Flyway 迁移保留 V1–V108 存量并按 core/biz 双目录拆分（业务新增从 V200 起）、自定义健康指标、DB 自检端点 + 测试类。
- **平台 / 基础设施类业务模块已落地（生产级）**：认证（JWT + 单点登录 jti 黑名单）、RBAC（角色 / 权限 / 菜单 / 数据范围）、字典、参数设置、文件（本地 + 对象存储抽象）、通知（含 `notify_global_switch` 总闸 fail-close）、协议、反馈、帮助、SSE 实时、审计、DB 自检，以及系统初始化与重置向导。
- **财务业务域（`fin_*`）**：发货 / 应收 / 收款 / 开票 / 核销（双台账）/ 退货全链路已落地，含 V98 发货承运商 + 附件、核销双台账不变量等。
- **AI 助手**：供应商字典化（OpenAI 直连 / DeepSeek / 扣子 / **Anthropic**）、提示词管理、L2 看板解读、草稿态工具调用、扣子插件回调（HMAC 验签 + nonce 防重放）、**会话历史 / 思考流持久化 / 记忆与多轮工具查询 / 回复内容支持操作 / 直连模式工具审计**。
- **知识库模块（2026-11-09 起落地）**：树形目录 + 文档 / 附件挂载、切片与检索（FTS 重建 V212）、向量数据库接入（embedding 供应商抽象）、三个扩展点开关、审计目标 `kb_doc` / `kb_attachment`。
- **登录防爆破（V202~V205）**：失败锁定 + 解锁权限 + 登录状态字典，配套 `LoginBruteForceTest` / `LoginRateLimitFilterTest`。
- **历史数据导入（2026-11-22）**：客户 / 货物导入模块（V219 导入权限），含模板校验与错误回执。
- ⚠️ **路径前缀约定**：前端经 vite proxy / 生产 nginx 以 `/api` 转发并去前缀；**后端真实路径不含 `/api`**（如 `/system/init/status`），白名单与安全配置一律按后端真实路径书写。
- ⚠️ **安全项（2026-10-19 已收口）**：`/system/db-check` 需有效 JWT（登录即可）；
  `/v3/api-docs`、`/doc.html`、`/swagger-ui/**` 由 `springdoc.api-docs.enabled` 控制（默认关闭，仅 dev 打开）；
  `/actuator/health` 需认证，只保留 `/actuator/health/liveness` 匿名供部署门禁轮询。
  JWT 密钥由环境变量 `JWT_SECRET` 注入（缺失即启动失败）。

## 安全契约（2026-11-04 收口）

RBAC 是**双线模型**：接口放行走 `role → sys_role_permission → sys_permission`，
前端按钮显隐走 `role → sys_role_menu → sys_menu`（必须 `menu_type=BUTTON` 且带 `perm_code`）。
新增受控能力必须双线补齐，详见 [`docs/开发规范.md`](docs/开发规范.md) 第十一~十四节。

已修复并钉成回归测试的越权 / 重放面（端点级断言，standalone MockMvc，不连库）：

| 契约 | 端点 | 断言 | 测试 |
| --- | --- | --- | --- |
| AI 草稿越权 | `GET /ai/conversation/{convId}/latest-draft` | **404** | `firestar-core` `AiDraftEndpointSecurityTest` |
| 应收详情越权 | `GET /receivable/{id}` | **404** | `firestar-biz` `BizSecurityBoundaryTest` |
| 核销反核销越权 | `POST /writeoff/reverse` | **404** | `firestar-biz` `BizSecurityBoundaryTest` |
| 插件回调无签名 | `POST /ai/coze/plugin` | **401** | `firestar-biz` `BizSecurityBoundaryTest` |

配套纯逻辑单测：`CozePluginVerifierTest`（验签 / 过期 / nonce 重放，6 例）、
`AiChatServiceTest`（草稿归属 404，3 例）、`InitStatusExposureTest`（init/status 降级，2 例）、
`SeedIntegrityTest`（种子完整性门禁，4 例）。

其余加固：写操作（反核销等）方法内二次归属过闸；只读汇总接口的权限点
（`writeoff:*`）按设计**仅授财务 / 管理员角色**；完整重置追加当前管理员密码二次确认；
初始化完成后 `init/status` 匿名端点只回 `initialized:true`。

## 本地运行

```bash
# 1. 准备本地 MySQL 库 firestar，复制凭据模板
cp .env/example.env .env/dev.env   # 填入 DB_PASSWORD（dev 的 JWT_SECRET 有 dev 专用回退值，可不填）

# 2. 启动（bin/start.sh 在 Windows 的 dev/test/pre 下会自动 mvn clean package 并拉起，监听 2025）
#    构建前置：先 taskkill 释放 2025 端口占用的 jar 锁，再清根 target 遗留旧布局 jar，最后 clean package
bash bin/start.sh 2025 /actuator/health/liveness

# 健康:     curl http://localhost:2025/actuator/health/liveness   # 匿名可用（部署门禁口径）
# 冒烟:     curl http://localhost:2025/ping
# 运维自检: curl -H "Authorization: Bearer <token>" http://localhost:2025/system/db-check
#           （登录即可，匿名调用返回 401）
# 完整健康: 启动时应用已写到 logs/app.log 的 `[startup-health]` 行
# 接口文档: http://localhost:2025/doc.html   # 仅 dev profile 开启
```

从源码直接跑（多模块 reactor，需指定应用模块）：

```bash
export DB_PASSWORD=...
mvn -o -pl firestar-server -am spring-boot:run
mvn -o compile                                   # 仅编译校验全 reactor（不连库）
mvn -o -pl firestar-core,firestar-biz -am test   # 端点级安全契约等纯逻辑/切片测试（不连库）
```

> `firestar-server` 的 `@SpringBootTest` 集成测试需**可达的 dev MySQL + `DB_PASSWORD`**，
> 与 CI 环境一致；`firestar-core` / `firestar-biz` 的安全契约测试用 standalone MockMvc，不连库。

## 与前端对接约定

| 项 | 值 |
| --- | --- |
| 后端地址（dev） | `http://localhost:2025` |
| 端口 | `2025` |
| git 仓库名 | `firestar-service` |
| 应用模块 / 产物 | `firestar-server` / `firestar-server-0.0.1-SNAPSHOT.jar` |
| 默认 profile | `dev` |
| 接口前缀 | 暂未配置统一前缀；前端 vite proxy 以 `/api` 转发并去前缀 |

## CI / CD

见 [`docs/云效率流水线配置.yaml`](docs/云效率流水线配置.yaml)：测试 + 构建 → 制品上传 → 主机部署（`/usr/local/server/java/firestar`）。
多模块后 jar 落在 `firestar-server/target/`，构建步会先归集到 `staging/`（jar + `bin/`）再上传，
避免上传仓库根 `target/` 导致制品缺 jar。`bin/start.sh`、`stop.sh` 的 `APP_NAME` 已同步为 `firestar-server`。
部署目标与 be-media-man-system **同台 ECS**，机器组复用；仅部署目录与端口（2025）不同。

## 分支约定

与参考项目一致：`develop → test →（人工确认）→ master → main → develop`。
详见 [`docs/git-workflow.md`](docs/git-workflow.md)。

## 变更记录

> 以下改动均已写入工作区源码，尚未 commit / 未部署；上线需提交后停服重打 jar，并与前端改动同次生效。

### 2026-11-01

- **V98 发货承运商 + 附件迁移**：`logistics_carrier` 字典 + 10 条热门承运商；`fin_shipment` 加 `tracking_no` / `carrier`；文件业务类型 / 审计目标补齐。`DatabaseCheckService.readSeedData` 期望值同步（后续 V104/V107 又调整到 35 / 146）。
- **发货服务改造**：`markShipped` 校验运单号非空 + 库内唯一 + 承运商启用态 + 附件至少 1 件，落库写审计。
- **系统公告只读端点**：`GET /system/config/announcement`（登录即可访问）。

### 2026-11-04（底座化 + 安全收口）

- **内核与业务分离**：单模块拆成 `firestar-parent`（根 pom）/ `firestar-core` / `firestar-biz` / `firestar-server` 四模块；Java 与 Flyway 迁移按归属拆分，迁移保留原版本号、新增业务迁移用 V200+。编译与模块边界守护脚本均通过。
- **工程归属纠偏**：整套多模块工程收进本 git 仓库内（此前误放在不被跟踪的 `D:\firestar-project` 容器层），文档与脚本一并归仓。
- **启停 / CI 同步**：`bin/start.sh`、`stop.sh`、`deploy.sh` 与云效流水线改为 `firestar-server` 口径；流水线用 `staging/` 归集 jar + bin；start.sh 增加清理根 `target` 陈旧 jar 的兜底，消除 `mvn clean` 被旧实例文件锁卡死。
- **7 项安全遗留项修复**：AI 草稿越权、应收详情越权、核销反核销缺归属校验、Coze 回调 nonce 重放、AI 路径无回归测试、完整重置无密码二次确认、`init/status` 匿名信息暴露。详见「安全契约」章节与 `docs/开发规范.md` 第十四节。
- **回归测试补齐**：新增 4 条端点级安全契约断言（404 / 404 / 404 / 401）+ 11 个纯逻辑单测；`firestar-core` 16/16、`firestar-biz` 3/3 通过。
- **方案 B：集成测试库隔离**：新建 `it` profile 与 `firestar_test` 独立库，从空库全量重放 Flyway 自建 schema + 种子；15 个 `@SpringBootTest` 统一切 `it`；`DatabaseCheckService` 期望值 public 化消除测试里的第二份硬编码；修复 8 个测试类的造数 / 权限用例缺陷（权限断言改走 MockMvc 端点级）。全量 `mvn test` **70/70 通过**，dev 库 11 张核心表行数与最大 ID 前后一致（零写入）。同时挖出并记录了 V27/V28/V75 硬编码库名导致「非 `firestar` 库名无法从空库初始化」的生产隐患。
- **通知类型中文名补全（V200）**：`V97` 种入 `INVOICE_PENDING_ISSUE` 模板时漏写 `template_name` 列，导致【通知记录】页「通知类型」下拉/类型列显示裸码。新增 `V200__invoice_pending_issue_template_name.sql` 幂等回填「待开票通知」，并即时同步 dev 库；同时把 db/migration 旧目录里只剩说明文件的 README 补上「迁移脚本在哪」的指路，避免拆分后找不到版本文件。该迁移不动 `sys_dict_type` / `sys_dict_data`，`DatabaseCheckService.readSeedData` 的 35 / 146 基线不变。

### 2026-11-05

- **枚举 ↔ 字典双写契约落地门禁**：新增 `V201__audit_target_type_ai_dict.sql` 补齐 `AI_PROMPT` / `AI_MODEL_CONFIG`（`audit_target_type` 11→13 项），`EXPECTED_DICT_DATA_COUNT` 146→148；`sys_dict_type.remark` 不再手写完整枚举清单。新增 `AuditDictConsistencyTest`（core，纯静态扫描、不连库，双向差集 + 锚点断言防假绿）并接进云效 `-Dtest` 白名单 —— 以后漏补字典 CI 直接红，漏改计数常量 `bash bin/test.sh` 直接红。
- **停用语义方案 A**：`sys_role` / `sys_permission` / `sys_role_permission` 三层查询补 `status=ENABLED`，`AuthServiceImpl` 的 `loadRoleIds` / `loadRoles` / `loadPermissions` 同步过滤（菜单 BUTTON 也补 status）。实时查库不缓存 → 停用后下个请求即生效，无需撤销 token；新增 `RbacStatusDisableTest` 6 个用例。全量 `bash bin/test.sh` **76/76 通过**，云效单测门禁 9/9 绿。
- **前端（firestar-manager）**：AI 助手抽屉宽度 420px → 600px。

### 2026-11-08 ~ 2026-11-22

- **登录防爆破（V202~V205）**：登录失败锁定、解锁权限点、登录状态字典启用；配套 `LoginBruteForceTest` / `LoginRateLimitFilterTest` / `LoginStatusDictEnabledTest`。
- **知识库模块（V206~V212）**：`kb_*` 表 + 菜单权限种子、文档树形结构列、FTS 重建、审计目标 `kb_doc` / `kb_attachment`；`ai/kb` 包落地切片 / 检索 / 向量接入（embedding 与 ES 属性配置化），三个扩展点开关可关。
- **AI 助手增强**：Anthropic 供应商模板、模型配置排序、N-13 直连模式工具审计补齐、思考流存储、会话历史对接、记忆与多轮工具查询、回复内容支持操作、DSML 异常修复、Excel 导出列宽 / 格式调优。
- **历史数据导入**：客户 / 货物导入模块（V219 导入权限），导出功能调优。
- **系统重置增强**：新增鉴权重置状态端点 `GET /system/reset/status`、重置执行管理员密码校验、验证码防爆破（IP + 5 次上限 + 一次性）、`lightReset` 补 `ai_%` / `FLW_RU_%` 清理、`fullReset` OSS 键收集与流程定义重部署。
- **测试与 CI**：server 测试类增至 23 个（新增 `DataScopeIsolationTest` 数据越权 4 例、`InitResetFlowTest`、`LoginBruteForceTest` 等）；云效 `-Dtest` 白名单扩到 8 类；`EXPECTED_DICT_DATA_COUNT` 基线 148 → 179（V206~V212 字典系列）。
- **流水线**：加装集成测试步、构建部署脚本优化、环境配置文件梳理（`.env` 化，凭据不进版本库）。
