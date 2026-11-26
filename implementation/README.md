# 实现工程说明（implementation）

登记日期：2026-11-26（V2）。记录当前工程、依赖版本、启动方式与本轮自测范围。
本文不代替documents/中的设计、准入和验收，也不表示任务已被人工批准或阶段已通过；
任务与准入见 [阶段计划](../documents/stages/阶段计划.md)、[任务索引](../documents/stages/任务索引.md)、
[阶段准入记录](../documents/stages/阶段准入记录.md)。

## 1 当前目录状态

| 目录/文件 | 真实状态 | 负责 |
|---|---|---|
| `backend/` | V2后端：cn.campus入口、交易与治理数据、V1—V5迁移、7个测试 | B |
| `backend/src/main/java/cn/campus/`中C的六个文件 | AdminBootstrap、AdminController、AuthController、CommunityController、SeedData、SessionSupport保持原样 | C |
| `frontend/` | V2前端：Vue 3、Vite与JavaScript页面 | A |
| `build.ps1` | 构建后端并校验`target/campus-resale-backend.jar`；找不到Maven时明确报错 | B |
| `run-local.ps1`、`start.cmd`、`stop.cmd` | 读取仓库根目录`.env.local`，启动后端jar与前端`serve.mjs`；需已构建前端`dist` | A/B |
| `Dockerfile`、`compose.yml`、`.env.example`、`.dockerignore` | 容器构建与服务编排配套 | B |
| `documents/A-V2/`、`documents/B-V2/` | 前端与后端的文件清单、来源记录及V3事项 | A/B |

后端实现内容与交接见 [backend/README.md](backend/README.md)；前端说明见 [A-V2前端说明.md](../A-V2前端说明.md)，后端说明见 [B-V2后端说明.md](../B-V2后端说明.md)。

## 2 实际环境登记（本机自测，2026-11-26）

| 项目 | 实际值 |
|---|---|
| 操作系统 | Windows（win32），PowerShell 7 |
| JDK | Zulu OpenJDK 21.0.3 LTS；后端源码`<release>17</release>` |
| 构建工具 | Apache Maven 3.9.9（本机位于`%USERPROFILE%\tools\apache-maven-3.9.9`，不入库） |
| 后端依赖 | Spring Boot 3.5.7、Spring Framework 6.2.12、mybatis-spring-boot-starter 3.0.4（MyBatis 3.5.17）、mysql-connector-j 9.4.0、HikariCP（Boot管理版本） |
| 数据库 | MySQL 8.4.13；本轮空库启动使用仓库外临时独立实例`127.0.0.1:3308`，未使用本机3306实例与既有库 |
| 数据库与账号 | 运行库、测试库由环境变量提供；真实凭据不写入仓库 |
| 后端端口 | 18080（`SERVER_PORT`可改） |
| 上传目录 | `implementation/backend/data/uploads`（`UPLOAD_DIR`可改，不入库） |
| 结构迁移 | V1 baseline、V2 indexes、V3 idempotency evidence audit、V4 lost update guards、V5 notification outbox；`schema_migrations`记录版本、说明、SHA-256与应用时间 |
| 前端开发端口 | 5173（`FRONTEND_PORT`可改） |

## 3 构建与运行

```powershell
# 构建：Maven与JDK 17+
powershell -ExecutionPolicy Bypass -File implementation\build.ps1
# 等价于
cd implementation\backend ; mvn -B package

# 运行：数据库地址与凭据只通过环境变量或未入库配置提供
$env:DB_URL      = 'jdbc:mysql://127.0.0.1:3306/campus?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&connectionTimeZone=UTC&serverTimezone=UTC'
$env:DB_USERNAME = '<数据库账号>'
$env:DB_PASSWORD = '<数据库口令>'
java -jar implementation\backend\target\campus-resale-backend.jar

# 健康检查与公开列表（无需登录）
curl.exe http://127.0.0.1:18080/api/health
curl.exe http://127.0.0.1:18080/api/items
```

空库首次启动需提供`BOOTSTRAP_ADMIN_USERNAME`与`BOOTSTRAP_ADMIN_PASSWORD`，或在仅限本地的演示环境设置`DEMO_SEED=true`。数据库首次启动自动建表；常规启动不删库、不覆盖账号与图片。配置项与容器说明见 [backend/README.md](backend/README.md) 与 [.env.example](.env.example)。

`run-local.ps1`同时启动前后端，需要仓库根目录`.env.local`、已构建的前端`dist/index.html`与`frontend/serve.mjs`；当前前端入口文件的核对事项见 [A-V2的V3差异记录](../documents/A-V2/V3差异记录.md)。

## 4 自测

### 4.1 测试环境

| 项目 | 实测值 |
|---|---|
| 测试库 | H2内存库，按测试类独立建库；`MarketMySqlIT`、`RootMySqlReviewIT`需`IT_DB_URL` |
| 测试配置 | `src/test/resources`下无独立固定配置文件，连接参数由用例或环境变量提供 |
| 构建工具 | Maven 3.9.9（本机`%USERPROFILE%\tools\apache-maven-3.9.9`，或用`MAVEN_HOME`/PATH） |
| 漏洞提示 | MySQL集成用例会清空目标测试库业务表，`IT_DB_URL`必须指向一次性测试库 |

### 4.2 执行方式

```powershell
$env:MAVEN_HOME = "$env:USERPROFILE\tools\apache-maven-3.9.9"
$env:Path = "$env:MAVEN_HOME\bin;" + $env:Path
cd implementation\backend
mvn -B test

# 或：构建并校验jar
powershell -ExecutionPolicy Bypass -File implementation\build.ps1
```

### 4.3 实测结果（2026-11-26）

- `mvn -B test`：50个测试，0失败、0错误、0跳过，BUILD SUCCESS，13.7秒；异常堆栈为断言用例的预期输出。
- `mvn -B -DskipTests package`：BUILD SUCCESS，`campus-resale-backend.jar`为26,702,667字节。
- 临时独立MySQL 8.4.13空库（`campus_v2_check`）启动：V1—V5迁移全部应用；`GET /api/health`返回200且`schema`为V5；`GET /api/items`返回200与空列表；`GET /api/me`返回200并下发CSRF令牌；初始管理员登录返回200；重启后迁移全部跳过、管理员不重复创建。
- 临时实例验证后已停止并删除数据目录；未连接本机3306实例或既有数据库。
- 未执行两个MySQL集成用例、容器镜像构建、前端联调与阶段验收。

2026-11-25的V1基线自测记录（43个测试、真实MySQL自测库）保留在Git历史中，不再作为V2的测试结论。

上述结果为AI按任务执行的自动化结果，**仍需实际成员核对预期、D独立复验**，不能替代阶段验收。

## 5 尚未包含与交接

| 项 | 负责 | 说明 |
|---|---|---|
| 真实MySQL上的交易并发、治理复核与两套集成用例 | B/D | 由V3专用测试库执行，见`documents/B-V2/V3差异记录.md` |
| 容器镜像构建与编排联调 | B/A/D | Dockerfile与compose已提供，尚未实际构建 |
| 前端页面构建、API代理与启动入口 | A | 构建产物需与后端静态资源或代理方式统一 |
| 联调机地址、账号与一键启停 | 全组 | 依赖环境登记与实际授权 |


## 本地启动补充

复制仓库根目录.env.local.example为.env.local并填写数据库及初始管理员；容器使用implementation/.env，二者用途不同。先在implementation/frontend运行npm ci及npm run build，再运行implementation/build.ps1，最后双击implementation/start.cmd。前端serve.mjs由Node运行，静态资源来自dist，/api同源代理到后端。
