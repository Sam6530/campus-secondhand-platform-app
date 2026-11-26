# 后端说明（V2）

最后更新：2026-11-26。工作分支：v2-b；目标分支：v2。技术栈：Spring Boot 3.5.7、Java 17、MyBatis 3.0.4、MySQL 8.4，测试使用 test-scope 的 H2 内存库。前端统一调用 `/api`。

本文件说明实现范围、运行配置和自测入口。功能与文件的对应关系见根目录 [B-V2后端说明.md](../../B-V2后端说明.md)，来源与逐文件校验见 [documents/B-V2/来源记录.md](../../documents/B-V2/来源记录.md)，尚未验证的集成事项见 [documents/B-V2/V3差异记录.md](../../documents/B-V2/V3差异记录.md)。

## 1 本版范围

| 范围 | 主要内容 | 主要入口 |
|---|---|---|
| 账号与会话 | 注册、登录、退出、校园认证提交与审核、账户限制与恢复、会话与 CSRF | `AuthController`、`AdminBootstrap`、`SessionSupport`、`SeedData` |
| 交易 | 发布、修改、撤下、检索分页、下单占用、接单拒单、交付、确认收货、取消、提醒 | `ItemController`、`OrderController`、`MarketService` |
| 治理 | 商品与认证审核、举报、履约争议与补证、隐藏、统计 | `MarketService`、`AdminController`、`CommunityController` |
| 社区数据 | 收藏、成交评价、卖方评价、物品咨询与订单沟通 | `CommunityController`、`MarketService` |
| 通知与审计 | 事务内写待投递通知，提交后投递；关键动作写审计 | `NotificationDelivery`、`MarketService` |
| 图片与健康检查 | 认证材料与物品图片上传、按对象授权读取；运行与迁移状态 | `AssetController`、`ImageGuard`、`HealthController` |

共 19 个 Java 类：13 个交易数据与基础设施类，6 个身份治理类。迁移脚本 V1–V5 覆盖空库基线、查询索引、提交意图回执、并发丢更新防护和事务性通知。

## 2 代码结构

```text
src/main/java/cn/campus
├─ MarketApplication.java   启动入口
├─ MarketService.java       交易、治理、社区与统计的业务实现
├─ Store.java / SqlMapper.java   参数化查询与固定 SQL 映射
├─ SchemaMigrator.java      迁移执行、校验和与 schema_migrations
├─ *Controller.java         物品、订单、图片、健康检查、身份、管理、社区接口
├─ ApiError.java            统一错误响应
├─ WebConfig.java           安全响应头与写请求 CSRF 校验
├─ AdminBootstrap.java      按运行配置创建初始管理员
├─ NotificationDelivery.java 事务提交后的通知投递
├─ SeedData.java            仅本地演示种子
└─ SessionSupport.java      会话与当前账户读取
src/main/resources/db/migration/V1–V5.sql
```

## 3 运行配置

应用只连接 MySQL 8.4，连接失败直接启动失败，不切换到其他数据库。配置键及默认值：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `SERVER_PORT` / `PORT` | `18080` | HTTP 端口 |
| `SERVER_ADDRESS` | `0.0.0.0` | 监听地址 |
| `DB_URL` | `jdbc:mysql://127.0.0.1:3306/campus?...` | 完整 JDBC 地址，优先于分散变量 |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `127.0.0.1` / `3306` / `campus` | 未提供 `DB_URL` 时拼接 |
| `DB_USERNAME` / `DB_USER` | `campus` | 数据库账号 |
| `DB_PASSWORD` | 空 | 数据库密码 |
| `DB_POOL_SIZE` | `10` | Hikari 连接数 |
| `DEMO_SEED` | `false` | 演示账户；交付环境必须为 false |
| `BOOTSTRAP_ADMIN_USERNAME` / `_PASSWORD` / `_NICKNAME` | 空 / 空 / `校园管理员` | 空库时创建初始管理员；密码需至少 12 位并含字母和数字 |
| `UPLOAD_DIR` | `./data/uploads` | 上传文件目录，相对后端工作目录 |
| `SESSION_COOKIE_SECURE` | `false` | HTTPS 交付时设为 true |
| `MIGRATION_LOCATIONS` | `classpath:db/migration` | 迁移脚本位置 |

数据库需使用 `utf8mb4` / `utf8mb4_0900_ai_ci`。空库首次启动会依次执行 V1–V5；每个脚本的 SHA-256 记录在 `schema_migrations`，已执行脚本不可修改，结构变化新增版本号。`GET /api/health` 返回数据库类型、迁移版本和运行状态。

## 4 构建与启动

```powershell
cd implementation/backend
mvn -B package
java -jar target/campus-resale-backend.jar
```

也可以在 `implementation` 目录执行 `powershell -ExecutionPolicy Bypass -File build.ps1` 构建后端并校验 `target/campus-resale-backend.jar`。前后端一并启动使用 `implementation/start.cmd`（调用 `run-local.ps1`，读取 `implementation/.env.local` 的 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 和 `SERVER_PORT`）。

容器方式：在 `implementation` 目录把 `.env.example` 复制为 `.env`，填写数据库密码与初始管理员，执行 `docker compose up --build -d`。容器使用 `campus` 库和独立命名卷，不提交填写后的 `.env`。

## 5 测试

```powershell
mvn -B test
```

默认测试使用 H2 内存库，不参与应用运行，也不进入交付 JAR。`MarketMySqlIT` 与 `RootMySqlReviewIT` 仅在设置 `IT_DB_URL` 时执行，且要求数据库名包含 `test`；真实 MySQL 验证需另行准备实例并按环境变量执行，不能用 H2 结果代替。

## 6 接口与协作边界

- 所有接口位于 `/api`，写请求需要会话中的 CSRF 令牌和 `X-CSRF-Token` 请求头；前端请求封装与页面见 `implementation/frontend`。
- 身份、会话、管理端点与社区接口由 C 维护；B 的实现调用同一套会话与账户表，不重复定义账号模型。
- 通知为站内消息；未接入邮件、短信或真实校园身份接口。认证由管理员人工审核，图片用于认证材料和物品。
- 本版不含在线支付、自动成交、定时取消和密码找回。

当前构建、测试与运行检查的实际结果记录在根目录 [B-V2后端说明.md](../../B-V2后端说明.md) 的“检查结果”一节；未执行或未通过的项目不在该节写成通过。
