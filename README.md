# firestar-service

火星财务 —— 后端服务（`firestar-` = backend）。

与前端 [`firestar-manager`](../firestar-manager) 一一对应；前端为 Vue 3 + Vite 管理后台。

## 技术栈（已确定）

> 对齐《火星财务系统技术栈文档》与参考项目 be-media-man-system 的工程结构。

- **Spring Boot 3.3.x** + **Java 17**（jakarta 命名空间）
- **Flowable 7.1** —— 审批引擎
- **MyBatis-Plus 3.5** —— ORM（Spring Boot 3 专用 starter）
- **Spring Security 6** —— 安全框架（后续接 JWT + RBAC）
- **Flyway 10** —— 数据库迁移
- **SpringDoc 2.6** —— 接口文档（OpenAPI3）
- **MySQL 8** / **Redis 7** —— 主库 / 缓存（技术栈共享基础设施）
- **Hutool 5.8** —— 工具库

> 参考项目 be-media-man-system 实际跑的是 Spring Boot 2.7.18；本仓库按技术栈文档升级到 3.3.x。

## 工程结构（截至 2026-10-12 实际状态）

> ⚠️ 历史 README 此处只画了 2 个文件（HomeController + SecurityConfig），与代码严重不符；以下为重新盘点后的真实结构。

```
src/main/java/com/firestar/
  FirestarApplication.java            # 主启动类
  config/                             # 5 个配置类
    SecurityConfig.java               # 占位安全配置（当前 permitAll 全放行，待接入 JWT+RBAC）
    MybatisPlusConfig.java            # MyBatis-Plus 分页 / 插件
    FlywayBeforeFlowableConfig.java   # Flyway 先于 Flowable 执行（规避启动竞态）
    AuditMetaObjectHandler.java       # 审计字段自动填充（create/update 时间、操作人）
    DatabaseTableHealthIndicator.java # 自定义健康指标（库表级）
  controller/                         # 多组控制器（认证 / RBAC / 字典 / 配置 / 文件 / 通知 / 协议 / 反馈 / 帮助 / 初始化重置 / 审计 等）
    HomeController.java               # 冒烟接口 /ping
    DatabaseCheckController.java      # 运维自检 /system/db-check（返回库名 / 表名 / 行数）
  service/                            # 业务 Service（当前 1 个，均为非业务运维类）
    DatabaseCheckService.java
  mapper/                             # 41 个 MyBatis-Plus Mapper 接口（零手写 SQL）
  entity/                             # 44 个实体（由 DDL 脚本生成，字段与库严格对齐；含 3 个抽象基类 BaseEntity/AuditedEntity/LogicDeleteEntity）
src/main/resources/
  db/migration/                       # 72 个 Flyway 迁移（V1–V72，持续累积）
    V1__baseline.sql                  # V1 基线：25 张业务表 DDL（含红线 / 金额公式注释）
    V2__init_dict_data.sql            # 33 类字典种子数据（sys_dict_data 共 144 条字典项）
    V3__flowable_schema.sql           # Flowable 审批引擎表
  application.properties              # 基础（端口 2025 / 应用名 / actuator）
  application-{dev,test,pre,prod}.properties
src/test/java/com/firestar/          # 9 个测试类（冒烟 / 自检 / 实体映射一致性 / 安全暴露等）
.env/example.env                     # 本地凭据模板（真实 .env/<profile>.env 不入库）
bin/start.sh stop.sh deploy.sh       # 跨平台运维脚本（复用参考项目实现）
docs/云效率流水线配置.yaml            # 阿里云效 Flow CI/CD（PaC）
docs/git-workflow.md                 # 分支同步 SOP
```

## 当前进度（截至 2026-11-01）

- **数据层 / 基础设施：已就绪** —— 业务表 DDL 全落地、实体 / Mapper 与库严格对齐、Flyway 迁移持续累积（截至本会话含 V98 发货承运商 + 附件迁移）、5 个配置类、自定义健康指标、DB 自检端点 + 测试类。
- **平台 / 基础设施类业务模块已落地（生产级）**：认证（JWT + 单点登录 jti 黑名单）、RBAC（角色 / 权限 / 菜单 / 数据范围）、字典、参数设置、文件（本地 + 对象存储抽象）、通知（含 `notify_global_switch` 总闸 fail-close）、协议、反馈、帮助、SSE 实时、审计、DB 自检，以及**系统初始化与重置向导**（`InitResetController` + `InitResetServiceImpl` + V36 菜单/权限种子；轻度重置清业务表 + Flowable 运行/历史 + 上传文件，完整重置走 Flyway `clean()+migrate()` 恢复出厂）。
- **财务业务域（`fin_*`）已随多轮迭代推进**：发货流程落地「承运商 + 运单号 + 附件」三部曲（V98 迁移：新增 `logistics_carrier` 字典 + 10 条热门承运商、`fin_shipment` 加 `tracking_no`/`carrier`、文件业务类型 `shipment_dispatch`、审计目标 `SHIPMENT`；`markShipped` 改为接收 `ShipmentMarkShippedReq` 并校验运单号库内唯一 + 附件必填）；其余 fin_* 业务（应收 / 收款 / 开票 / 核销 / 退货）随接口逐步补齐。
- ⚠️ **路径前缀约定**：前端经 vite proxy / 生产 nginx 以 `/api` 转发并去前缀；**后端真实路径不含 `/api`**（如 `/system/init/status`），白名单与安全配置一律按后端真实路径书写。
- ⚠️ **安全项（2026-10-19 已收口）**：`/system/db-check` 需有效 JWT（登录即可，不再要求细粒度权限点）；
  `/v3/api-docs`、`/doc.html`、`/swagger-ui/**` 由 `springdoc.api-docs.enabled` 控制（默认关闭，仅 dev 打开）；
  `/actuator/health` 需认证，只保留 `/actuator/health/liveness` 匿名供部署门禁轮询。
  JWT 密钥由环境变量 `JWT_SECRET` 注入（缺失即启动失败），详见 `application.properties` 的 JWT 段注释。

## 本地运行

```bash
# 1. 准备本地 MySQL 库 firestar，复制凭据模板
cp .env/example.env .env/dev.env   # 填入 DB_PASSWORD（dev 的 JWT_SECRET 有 dev 专用回退值，可不填）

# 2. 启动（bin/start.sh 会自动 mvn package 并拉起，监听 2025）
bash bin/start.sh 2025 /actuator/health/liveness

# 健康:     curl http://localhost:2025/actuator/health/liveness   # 匿名可用（部署门禁口径）
# 冒烟:     curl http://localhost:2025/ping
# 运维自检: curl -H "Authorization: Bearer <token>" http://localhost:2025/system/db-check
#           （登录即可，匿名调用返回 401）
# 完整健康: 启动时应用已写到 logs/app.log 的 `[startup-health]` 行（bin/start.sh 就绪后会直接打印）
#           /actuator/health 本身需认证，别再拿它做匿名探针
# 接口文档: http://localhost:2025/doc.html   # 仅 dev profile 开启
```

也可直接用 Maven：`mvn spring-boot:run`（需先 export DB_PASSWORD）。

## 与前端对接约定

| 项 | 值 |
| --- | --- |
| 后端地址（dev） | `http://localhost:2025` |
| 端口 | `2025` |
| 应用名 | `firestar-service` |
| 默认 profile | `dev` |
| 接口前缀 | 暂未配置统一前缀；前端 vite proxy 以 `/api` 转发并去前缀 |

## CI / CD

见 [`docs/云效率流水线配置.yaml`](docs/云效率流水线配置.yaml)：测试 + 构建 → 制品上传 → 主机部署（`/usr/local/server/java/firestar`）。
部署目标与 be-media-man-system **同台 ECS**，机器组复用；仅部署目录与端口（2025）不同。

## 分支约定

与参考项目一致：`develop → test →（人工确认）→ master → main → develop`。
详见 [`docs/git-workflow.md`](docs/git-workflow.md)。

## 近期变更（截至 2026-11-01，代码已落地、待 git 提交与部署重启）

> 以下改动均已写入工作区源码，但尚未 commit / 未部署；上线需提交后停服重打 jar（Flyway 会自动执行 V98 迁移），并与前端改动同次生效。

- **V98 发货承运商 + 附件迁移**（`db/migration/V98__shipment_dispatch_carrier_and_attachment.sql`）：`logistics_carrier` 字典 + 10 条热门承运商（SF / YTO / ZTO / STO / YD / JD / EMS / BEST / DBL / JT，均 ENABLED，按热门排序）；`fin_shipment` 加 `tracking_no` / `carrier`；`sys_file_storage` 业务类型加 `shipment_dispatch`；`audit_target_type` 加 `SHIPMENT`。
- **发货服务改造**：`Shipment.java` 加 `trackingNo` / `carrier`；`ShipmentVO` 加 `trackingNo` / `carrier` / `carrierName` / `dispatchFileIds`；新增 `ShipmentMarkShippedReq`（`trackingNo` / `carrier` / `fileIds`）；`markShipped` 签名改为接收该 req，并校验运单号非空 + 库内唯一 + 承运商须为启用态字典值 + 附件至少 1 件；落库后 `@Auditable` 写 `sys_audit_log`。⚠️ **探针常量同步**：`DatabaseCheckService.readSeedData` 的 `dictTypeCount` 33→34、`dictDataCount` 130→142（V98 新增 1 类字典 + 12 条字典项），否则 `/system/db-check` 会因条数对不上报 503。
- **系统公告只读端点**：`ConfigController` 新增 `GET /system/config/announcement`（无 `@RequirePerm`，登录即可访问），复用 `ConfigService.getConfigValue("login_notice")` 返回公告文本；与既有 `/config/flags` 同范式。
