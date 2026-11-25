# 实现工程说明（implementation）

登记日期：2026-11-25。记录本轮**真实存在**的工程、依赖版本、启动方式与自测范围。
本文不代替 documents/ 中的设计、准入和验收，也不表示任务已被人工批准或阶段已通过；
任务与准入见 [阶段计划](../documents/stages/阶段计划.md)、[任务索引](../documents/stages/任务索引.md)、
[阶段准入记录](../documents/stages/阶段准入记录.md)。

## 1 当前目录状态

| 目录/文件 | 真实状态 | 负责 |
|---|---|---|
| `backend/` | V1 交易数据、迁移与基础通知后端已有可运行实现与自测 | B |
| `frontend/` | 尚无代码（占位） | A（C 的管理页在 A 的公共工程上） |
| `build.ps1` | 可构建后端；找不到 Maven 时明确报错，不静默成功 | B |
| `Dockerfile`、`compose.yml`、`start.cmd`、`stop.cmd` | 仍为空占位；容器/一键启停需先确认联调方式与端口（Q-RB-01），由当轮指定维护者合并 | 全组 |

后端实现内容与交接见 [backend/README.md](backend/README.md)。

## 2 实际环境登记（本机自测，2026-11-25）

| 项目 | 实际值 |
|---|---|
| 操作系统 | Windows（win32），PowerShell 7 |
| JDK | Zulu OpenJDK 21.0.3 LTS；后端源码 `<release>17</release>` |
| 构建工具 | Apache Maven 3.9.9（本机位于 `%USERPROFILE%\tools\apache-maven-3.9.9`，不入库） |
| 后端依赖 | Spring Boot 3.5.7、Spring Framework 6.2.12、mybatis-spring-boot-starter 3.0.4（MyBatis 3.5.17）、mysql-connector-j 9.4.0、HikariCP（Boot 管理版本） |
| 数据库 | MySQL 8.4.13；自测用独立合成实例 `127.0.0.1:3307`，数据目录在 `%USERPROFILE%\tools\mysql84-dev`（不入库，未使用他人已运行的 3306 实例与数据） |
| 数据库与账号 | `campus_resale_app`（运行库）、`campus_resale_selftest`（自测库）；本机合成实例账号未写入仓库，实际凭据通过环境变量提供 |
| 后端端口 | 18080（`SERVER_PORT` 可改） |
| 上传目录 | `implementation/backend/var/uploads`（`UPLOAD_DIR` 可改，不入库） |
| 结构迁移版本 | `V1`（`schema_migrations` 记录脚本名、SHA-256 与时间；重启只校验不重跑） |
| 前端开发端口 | 5173（约定值，A 尚未实现，未验证） |

> 本机自测实例的启动方式仅作记录，不是交付脚本：
> `mysqld --datadir=%USERPROFILE%\tools\mysql84-dev\data --port=3307 --bind-address=127.0.0.1 --named-pipe=OFF --mysqlx=OFF`。
> 未修改系统自带的 MySQL84 服务（3306）与其数据；正式联调实例的地址/端口与账号仍需 Q-RB-01 结论后登记。

## 3 构建与运行

```powershell
# 构建（只需 Maven 与 JDK 17+）
powershell -ExecutionPolicy Bypass -File implementation\build.ps1
# 等价于
cd implementation\backend ; mvn -B package

# 运行：数据库地址与凭据只通过环境变量或本机未入库配置提供
$env:DB_URL      = 'jdbc:mysql://127.0.0.1:3307/campus_resale_app?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&connectionTimeZone=UTC&serverTimezone=UTC'
$env:DB_USERNAME = '<本机合成库账号>'
$env:DB_PASSWORD = '<本机合成库口令>'
java -jar implementation\backend\target\campus-resale-backend.jar

# 健康检查与公开列表（无需登录）
curl.exe http://127.0.0.1:18080/api/health
curl.exe http://127.0.0.1:18080/api/items
```

数据库首次启动自动建表；常规启动不删库、不覆盖账号与图片。

## 4 配置项

| 变量 | 用途 | 当前默认 |
|---|---|---|
| `SERVER_PORT` | 后端端口 | 18080 |
| `SERVER_ADDRESS` | 监听地址 | 0.0.0.0 |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 各环境数据库与应用账号 | 本机 3307 合成库，不入库真实凭据 |
| `DB_POOL_SIZE` | 连接池上限 | 10 |
| `UPLOAD_DIR` / `UPLOAD_MAX_BYTES` | 图片目录与单张上限 | `./var/uploads` / 5242880 |
| `NOTIFICATION_BATCH_SIZE` / `RETRY_INTERVAL_SECONDS` | 补投批次与失败重试间隔 | 50 / 30 |
| `MIGRATION_ENABLED` | 是否在启动时执行结构迁移 | true |

日志不打印口令、会话、CSRF 令牌与认证原件。

## 5 自测

```powershell
cd implementation\backend ; mvn -B test
```

2026-11-25 实跑结果：**Tests run: 30, Failures: 0, Errors: 0, Skipped: 0（BUILD SUCCESS）**，
执行库为独立合成 MySQL 8.4.13（`campus_resale_selftest`），并发用例使用两个真实数据库连接与屏障。

同一日真实启动冒烟（`campus_resale_app`）：

- `GET /api/health` → 200 `{"status":"UP","database":"MySQL","schema":"V1","checkedAt":...}`
- `GET /api/items` 匿名 → 200 `[]`
- 无会话 `POST /api/items` → 401 `{"message":"请先登录"}`
- 未实现接口 `GET /api/favorites` → 404 `{"message":"接口或资源不存在"}`（不返回 500）
- 合成物品公开列表 → 200，`seller_id` 为公开引用、标题手机号被替换为 `[联系方式已隐藏]`、`price` 为 9.90
- 重启后健康检查仍为 `schema=V1`，已有数据保留，日志不再重复执行迁移

上述结果为 AI 按任务执行的自动化结果，**仍需实际成员核对预期、D 独立复验**，不能替代阶段验收。

## 6 尚未包含与交接

| 项 | 负责 | 说明 |
|---|---|---|
| 会话/CSRF、注册登录、认证材料、/api/me | C | B 只消费 `ActorProvider`；`/api/me` 的 `user.id` 需返回 `accounts.public_ref` |
| 管理端点 `POST /api/admin/{kind}/{id}` | C | 审核物品时在同一应用事务调用 `ItemReviewService.review(...)` |
| 收藏、评价与信誉、统计、争议入口、治理联动、通知重试策略完善 | B（V2） | V1 只做必要守卫与基础通知 |
| 前端页面、/api 代理 | A | 契约字段以 `documents/design/openapi.yaml` 为准 |
| 一键启停脚本、容器化、联调机地址 | 全组 | 依赖 Q-RB-01 与实际环境登记 |

