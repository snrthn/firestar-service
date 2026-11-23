# 更新日志（Changelog）

本项目的所有重要变更将记录在本文件中。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## 0.1.0 - 2026-11-23

自 v0.0.1 以来的第三个迭代周期（2026-11-05 ~ 2026-11-23），对应前端仓库同期发布。

### Added

- **登录防爆破（V202~V205）**：登录失败锁定、解锁权限点、登录状态字典；配套 `LoginBruteForceTest` / `LoginRateLimitFilterTest` / `LoginStatusDictEnabledTest`。
- **知识库模块（V206~V212）**：`kb_*` 表 + 菜单权限种子、文档树形结构列、FTS 全文索引重建、审计目标 `kb_doc` / `kb_attachment`；`ai/kb` 包落地切片 / 检索，**ES 向量混合检索**（embedding 供应商抽象 + RRF 融合），三个扩展点开关可配置。
- **AI 助手增强**：Anthropic 供应商模板、模型配置 `model_type` 四类 + 排序（V214/V215）、思考流存储（V216）、赞踩反馈（V217）、N-13 直连模式工具审计、会话历史 / 记忆与多轮工具查询、DSML 异常修复、工具扩至 28 个、多轮工具循环护栏 `ToolLoopPolicy`、扣子 `submit_tool_outputs` 内联工具循环。
- **历史数据导入（V218/V219）**：客户 / 货物等 9 个导入 Handler（异步流水线 PARSING→VALIDATING→WRITING→RECONCILING，2000 行闸门 + 错误行回执）。
- **系统重置增强**：鉴权重置状态端点 `GET /system/reset/status`、重置执行管理员密码校验、验证码防爆破（IP + 5 次上限 + 一次性）、`lightReset` 补 `ai_%` / `FLW_RU_%` 清理、`fullReset` OSS 键收集与流程定义重部署。
- **客户 / 货物主表**：`fin_customer`（V68）、`fin_goods`（V71），删除 `fin_project.customer_name` 冗余列（V72）。
- **数据看板**：`StatsController` 7 个只读端点（L1 五张 Tab + L2 账龄），主体级精确 FIFO 账龄（`StatsAgeingTest` 钉口径）。

### Fixed

- 应收单已核销列（`written_off_amount`）同步回写（D2 修复）。
- 扣子插件回调 HMAC 验签 + nonce 防重放；Coze 记忆断层问题处理。

### 测试与 CI

- 三模块测试规模 79 个测试类（core 45 / biz 11 / server 23），新增 `DataScopeIsolationTest` 数据越权 4 例等；云效 `-Dtest` 白名单扩到 8 类；`EXPECTED_DICT_DATA_COUNT` 基线 148 → 179。
- 流水线：加装集成测试步、构建部署脚本优化、环境配置 `.env` 化。

## 0.0.1 - 2026-11-02

首个发布版本（tag: `v0.0.1`）。

### Added

- Spring Boot 多模块架构：`firestar-core`（平台内核）+ `firestar-biz`（火星业务）+ `firestar-server`（应用入口），内核 / 业务分离与双 migration location。
- 基础 RBAC：Role 枚举 + StaffRole 两层角色、`@RequirePerm` 权限引擎、审计日志（`@Auditable` ↔ 字典一致性门禁）。
- JWT + BCrypt 鉴权，验证码登录，租户模型预留。
- Flowable 工作流审批。
- 应收 / 发票 / 收款 / 核销 / 发货 / 退货 / 协议版本等核心业务域。
- 系统安装 / 初始化 / 重置框架，数据库自检（表数 / 字典基线）。
- 阿里云 OSS 文件存储（签名 URL + 自定义域名）、阿里云 SMS 网关（密钥 `enc:v1:` 加密）。

