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
  controller/                         # 2 个控制器
    HomeController.java               # 冒烟接口 /ping
    DatabaseCheckController.java      # 运维自检 /system/db-check（返回库名 / 表名 / 行数）
  service/                            # 业务 Service（当前 1 个，均为非业务运维类）
    DatabaseCheckService.java
  mapper/                             # 25 个 MyBatis-Plus Mapper 接口（零手写 SQL）
  entity/                             # 28 个实体（由 DDL 脚本生成，字段与库严格对齐）
src/main/resources/
  db/migration/                       # 3 个 Flyway 迁移
    V1__baseline.sql                  # 25 张业务表 DDL（含红线 / 金额公式注释）
    V2__init_dict_data.sql            # 22 类字典种子数据
    V3__flowable_schema.sql           # Flowable 审批引擎表
  application.properties              # 基础（端口 2025 / 应用名 / actuator）
  application-{dev,test,pre,prod}.properties
src/test/java/com/firestar/          # 5 个测试类（冒烟 / 自检 / 实体映射一致性等）
.env/example.env                     # 本地凭据模板（真实 .env/<profile>.env 不入库）
bin/start.sh stop.sh deploy.sh       # 跨平台运维脚本（复用参考项目实现）
docs/云效率流水线配置.yaml            # 阿里云效 Flow CI/CD（PaC）
docs/git-workflow.md                 # 分支同步 SOP
```

## 当前进度（截至 2026-10-12）

- **数据层 / 基础设施：已就绪** —— 25 张表 DDL 全落地、28 实体、25 Mapper、3 个 Flyway 迁移（含字典种子）、5 个配置类、自定义健康指标、1 个真实可用的 DB 自检端点 + 5 个测试类。
- **业务功能：尚未开始** —— 业务 Controller / Service 为零（仅 `/ping` 冒烟与 `/system/db-check` 运维自检）；接口文档规划的 ~100 个 API / 15 模块均未实现；JWT + RBAC 未接入；Flowable 仅建表、流程定义 / 任务处理未动。
- **⚠️ 已知安全项**：`SecurityConfig` 当前 `permitAll()` 全放行，任何人可访问 `/system/db-check` 拿到库名 / 表名。属占位阶段，接 JWT+RBAC 后需将 `/system/**` 收进鉴权（类注释已 TODO）。

## 本地运行

```bash
# 1. 准备本地 MySQL 库 firestar，复制凭据模板
cp .env/example.env .env/dev.env   # 填入 DB_PASSWORD

# 2. 启动（bin/start.sh 会自动 mvn package 并拉起，监听 2025）
bash bin/start.sh 2025 /actuator/health

# 健康: curl http://localhost:2025/actuator/health
# 冒烟:        curl http://localhost:2025/ping
# 运维自检:    curl http://localhost:2025/system/db-check
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
