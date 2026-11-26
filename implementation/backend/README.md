# 后端（V1，B 负责部分）

最后更新：2026-11-25。技术栈沿用候选基线（ADR-RB-01/02/04）：Spring Boot 模块化单体 + MyBatis + MySQL，
前端统一 `/api`。**技术接受、任务准入和阶段验收仍需实际成员记录**，本文件只是实现与自测记录。

## 1 本版实现范围（对应 TASK-V1-02/04/05/07）

| 范围 | 已实现 | 主要入口 |
|---|---|---|
| 工程骨架、共享迁移、健康检查 | 空库自动建表、校验和记录、重复启动不重跑；`/api/health` 可定位结构版本 | `shared/db/DatabaseMigrationRunner`、`HealthController` |
| 物品发布/修改/撤下/检索 | 版本化、关键修改重审、有效订单禁止改标的、撤下与显式重提、公开投影脱敏 | `trade/item/ItemService` |
| 物品版本审核（受控动作） | 仅当前待审版本可通过/退回，旧版本审核不生效 | `trade/admin/ItemReviewService` |
| 订单快照与唯一占用 | 下单原子建立订单/快照/占用/回执/通知；竞争 409 | `trade/order/OrderService#create` |
| 接单/拒单/交付/确认/取消/提醒 | 阶段与角色校验、事实声明、争议守卫、释放占用并重判可售 | `trade/order/OrderService#act` |
| 基础通知与失败边界 | 业务同事务写 Outbox，提交后投递；失败补投不重跑业务 | `notification/Outbox*`、`NotificationController` |
| 共享图片资源入口 | 上传校验、公开物品图片匿名只读、其余按对象授权 | `asset/AssetService` |
| 必要审计 | 发布/修改/撤下/审核/订单动作同事务写 `audit_facts` | `shared/audit/AuditService` |

不在本版：注册登录/会话/CSRF（C）、管理端点（C）、聊天（C）、收藏评价信誉统计（V2）、争议入口与治理联动（V2）。

## 2 代码结构

```text
src/main/java/com/campus
├─ CampusApplication.java
├─ shared/          api(统一错误) auth(ActorProvider 契约) account(账户只读与资格) audit
│                   db(UTC/迁移/健康检查) idempotency(意图键与回执) text(公开脱敏)
├─ trade/item/      物品行/版本/查询、发布修改撤下、公开与本人投影
├─ trade/order/     订单、占用、交付、阶段操作
├─ trade/admin/     物品版本审核受控动作（供 C 的管理端点调用）
├─ notification/    Outbox、投递器、站内通知读取
└─ asset/           图片元数据、上传与对象授权读取
src/main/resources/db/migration/V1__trade_core.sql
```

## 3 关键契约与跨模块边界

| 契约 | 形式 | 交接说明 |
|---|---|---|
| INT-RB-AUTH | 接口 `shared/auth/ActorProvider`（返回 `ActorContext(userId)`） | C 提供基于服务端 Session 的实现；未实现时默认 Bean 返回空 → 受保护操作 401。B 每次再读 `accounts` 当前资格，不信任前端角色字段 |
| `/api/me` 的 id | 约定 | 需返回 `accounts.public_ref`，与 `seller_id`/`buyer_id` 同口径；内部主键不外发 |
| INT-RB-TRADE | `ItemReviewService.review(itemId, version, pass, reason)` | C 的管理端点在管理权限与参数绑定后于同一应用事务调用；B 保留锁后复核 |
| EVT-RB-NOTICE | `OutboxService.append(eventKey, recipientId, eventType, content)` | 业务同事务写入；投递由 `OutboxDispatcher` 在提交后执行，失败不改业务 |
| INT-RB-ASSET | `POST/GET /api/assets` | 公开物品图片匿名只读；认证材料/私有资源需 Session 与对象授权（C 复核 VERIFY 语义） |

## 4 已落实的业务约束（摘录）

- 订单/快照/占用/回执/审计/待发送通知同一事务成立；失败整体回滚，不返回成功（TC-RB-09/26）。
- 占用由 `reservations` 主键 `item_id` + 订单唯一约束共同保证，竞争失败返回 409（TC-RB-08）。
- 锁序：账户 → 物品 → 订单。阶段操作先静态读定位，再锁物品、锁订单，锁后重读阶段与争议标记（TC-RB-11）。
- 意图键：`operation_receipts(actor_id, action, idem_key)` 唯一；同键同内容回原结果、同键异内容 400、
  他人同键不共享回执；回执不因账户后续受限失效（TC-RB-07/27）。
- 只有该订单买方本人可确认收到；管理员与卖方均 403（TC-RB-13）。
- 普通取消须当事人、未交付声明、原因、无交付登记、无处理中争议；已登记交付转争议处理（TC-RB-11/12）。
- 处理中争议（`orders.disputed=1`）暂停接单/拒单/交付/普通取消，保留查询、沟通与买方本人确认（BR19 守卫）。
- 成交与终止都释放占用；成交流程把物品置「已成交」，终止重判「在售」，隐藏与账户受限仍阻断公开（BR03/12/13）。
- 公开列表只返回「在售 + 未隐藏 + 卖方未受限 + 无占用 + 当前版本通过」的物品；标题/描述/昵称按 BR14 脱敏，
  数据库原文不改写（TC-RB-24）。
- 认证材料与物品图片的上传资格分开：`VERIFY` 允许未认证用户上传（否则无法提交认证），`ITEM` 要求已认证，
  受限账户一律禁止；伪类型/空文件/超限返回 400（TC-02/04/24）。
- 治理归属的列只由治理方写：B 的更新只改 `items.status/current_version/image_id/updated_at` 与
  `orders.stage/reason/completed_at/reminded`，不写 `hidden/relist_allowed/disputed`，避免覆盖 C 的治理结果。
- 请求体按契约必填，但**先判登录再判内容**：未登录缺 body 返回 401，已登录缺 body 返回 400；
  缺表单字段/内容类型不支持/超限均返回 400；公开检索 `page` 上限 5000，越界或整数溢出范围返回 400。
- 同键并发：先成立的请求写回执，后到者锁后重读回执返回同一结果（下单/编辑），不再报占用冲突或版本冲突。
- 通知补投只处理当前收件人的待发送记录；失败达上限转 `DEAD` 并记错误日志，记录保留可人工核查。
- 下单同时校验卖方已认证且未受限；管理员不能审核本人发布的物品（403）。

## 5 数据与迁移

- 迁移序列由 B 维护：`V1__trade_core.sql` 建立 `accounts`（共享核心，凭据/认证等由 C 新增迁移扩展）、
  `items`、`item_versions`、`orders`、`reservations`、`deliveries`、`operation_receipts`、
  `notification_outbox`、`notifications`、`audit_facts`、`assets`。
- `schema_migrations` 记录版本、脚本名、SHA-256、执行时间；已执行脚本被改动会**启动失败**，要求新增升级脚本。
- 迁移执行前会先取 MySQL 命名锁，意图串行化多实例启动；**当前实现未校验 `GET_LOCK` 的返回值**，
  锁超时后仍会继续（已在代码审查中列为缺陷）。修复前请避免多个实例同时首次启动做迁移。
- 种子数据与迁移分离，本版未附带种子；自测通过测试夹具生成合成角色与物品。
- 治理归属的列：`items.hidden`、`items.relist_allowed`、`orders.disputed` 由 C 的治理写入，B 只读不写；
  B 的更新使用列级语句（`ItemMapper.updateStatus/updateVersionPointer`、`OrderMapper.updateStage/markReminded`）。
- `notification_outbox.status` 取值为 `PENDING/SENT/FAILED/DEAD`；`DEAD` 表示达到重试上限、保留待人工核查。
- 金额 `DECIMAL(12,2)`（正数，最大 9999999999.99）；时间统一 UTC `DATETIME(3)`，响应 ISO-8601 `Z`。

## 6 可运行测试环境与启动方法（从零复现，2026-11-25 实测）

本节写的是**实际跑过**的环境与命令，与第 7 节的结果同一批执行。自测只使用本机独立合成库，
不连接联调机、不连接他人实例、不读写真实账号数据。

### 6.1 需要的组件（实测版本）

| 组件 | 实测值 | 说明 |
|---|---|---|
| 操作系统 | Windows（win32）+ PowerShell 7 / PowerShell 5.1 | `build.ps1` 为 UTF-8 BOM，PS 5.1 也可执行 |
| JDK | Zulu OpenJDK 21.0.3 LTS | 源码 `<release>17</release>`，JDK 17+ 均可构建 |
| Maven | Apache Maven 3.9.9 | 本机在 `%USERPROFILE%\tools\apache-maven-3.9.9`（不入库），也可装到 PATH |
| MySQL | MySQL 8.4.13 | 需 8.0+：迁移使用 `utf8mb4_0900_ai_ci` 与 `CHECK` 约束 |
| 数据库实例 | `127.0.0.1:3307`，数据目录 `%USERPROFILE%\tools\mysql84-dev\data` | 独立于系统 MySQL84 服务（3306），可随时丢弃重建 |
| 测试库 | `campus_resale_selftest` | 每个用例都会清空其中的业务表，**只能**用于自测 |
| 运行库（冒烟用） | `campus_resale_app` | 手工启动应用时使用 |
| 后端端口（冒烟） | 18080 | 可用 `SERVER_PORT` 修改 |

> 不依赖 Docker/Testcontainers：自测直接使用本机 MySQL 真实连接；并发用例开两个真实连接 + 屏障。

### 6.2 一次性准备：起一个独立合成 MySQL 实例（Windows 实测命令）

```powershell
$mysqlBin = 'C:\Program Files\MySQL\MySQL Server 8.4\bin'
$base     = "$env:USERPROFILE\tools\mysql84-dev"
New-Item -ItemType Directory -Force -Path "$base\data", "$base\logs" | Out-Null

# 仅首次：初始化（root 空口令；只用于这台一次性实例，绝不复用到有真实数据的库）
& "$mysqlBin\mysqld.exe" --initialize-insecure --datadir="$base\data" `
    --basedir='C:\Program Files\MySQL\MySQL Server 8.4'

# 启动：只回环 + 3307，关闭命名管道与 X 协议，避免与系统 3306 服务互相影响
Start-Process -FilePath "$mysqlBin\mysqld.exe" -WindowStyle Hidden -ArgumentList @(
    "--datadir=$base\data", '--port=3307', '--bind-address=127.0.0.1',
    '--named-pipe=OFF', '--mysqlx=OFF', "--log-error=$base\logs\mysql-error.log")

# 等待就绪（约 10-15 秒）后验证
& "$mysqlBin\mysqladmin.exe" -h 127.0.0.1 -P 3307 -u root ping   # 期望：mysqld is alive

# 建运行库与自测库（只需一次；重复执行安全）
& "$mysqlBin\mysql.exe" -h 127.0.0.1 -P 3307 -u root -e @"
CREATE DATABASE IF NOT EXISTS campus_resale_app      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS campus_resale_selftest CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
"@
```

已有一套可用 MySQL 8.0+ 时也可以复用，但必须**新建专用测试库**并通过 `DB_URL` 指过去（见 6.3），
因为测试会清空业务表；不要指向联调库或含真实账号的库。

### 6.3 测试读取的配置（`src/test/resources/application-test.yml`）

| 配置 | 默认值 | 说明 |
|---|---|---|
| `spring.datasource.url` | `jdbc:mysql://127.0.0.1:3307/campus_resale_selftest?...&connectionTimeZone=UTC&serverTimezone=UTC` | 可用 `DB_URL` 覆盖 |
| `spring.datasource.username` / `password` | `root` / 空 | 只适用于 6.2 的一次性实例；其他环境用 `DB_USERNAME`/`DB_PASSWORD` |
| `campus.upload.dir` | `./target/test-uploads` | 测试不写入真实图片，目录可以不存在 |
| `campus.upload.max-bytes` / `max-pixels` | `5242880` / `16000000` | 单张容量与像素上限（候选值，见 B-07） |
| `spring.servlet.multipart.max-request-size` | `6291456` | 请求体上限需大于单文件上限，否则 5MB 图片会因 multipart 开销被拒 |
| `campus.notification.retry-delay-seconds` | `0` | 让「投递失败 → 恢复后补投」能在同一用例内完成 |
| `campus.notification.max-attempts` | `100` | 达到上限转 `DEAD`（保留记录、停止自动重试），可用 `NOTIFICATION_MAX_ATTEMPTS` 调整 |
| `campus.migration.enabled` | `true` | 测试库同样执行真实迁移（当前为 `V1`） |

覆盖示例：

```powershell
$env:DB_URL      = 'jdbc:mysql://127.0.0.1:3307/campus_resale_selftest?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&connectionTimeZone=UTC&serverTimezone=UTC'
$env:DB_USERNAME = 'root'
$env:DB_PASSWORD = ''
```

> 注意：`$env:DB_URL = ''`（空串）会覆盖默认值，报 `Failed to determine a suitable driver class`；
> 想用默认值时请 `Remove-Item Env:DB_URL`，不要设成空串（本轮实际踩过）。

### 6.4 启动测试（三条命令就够）

```powershell
# 0) PATH 里没有 mvn 时，指向本机解压目录（或自行加入 PATH）
$env:MAVEN_HOME = "$env:USERPROFILE\tools\apache-maven-3.9.9"
$env:Path = "$env:MAVEN_HOME\bin;" + $env:Path

# 1) 全量自测：真实 MySQL + 43 个用例（含并发与故障注入）
cd implementation\backend
mvn -B test

# 2) 只看结论
mvn -B test | Select-String 'Tests run:|BUILD'

# 3) 单类 / 单方法（定位问题时）
mvn -B test -Dtest=OrderConcurrencyTest
mvn -B test -Dtest=OrderLifecycleTest#fullHappyPath
```

含打包的一键构建（先跑完自测再打包 jar）：

```powershell
powershell -ExecutionPolicy Bypass -File implementation\build.ps1
```

期望输出（2026-11-25 实测）：9 个测试类共 **43 个用例、0 失败**，最后 `BUILD SUCCESS`；
明细报告在 `implementation/backend/target/surefire-reports/`（每类一个 `.txt`/`.xml`）。
各类用例数与覆盖映射见第 7 节。

### 6.5 测试对数据库做了什么（务必先读）

- 每个用例 `@BeforeEach` 调用 `TestFixtures.reset()`：按外键顺序 **DELETE 11 张业务表的数据**
  （`notifications` → `notification_outbox` → `deliveries` → `reservations` → `audit_facts`
  → `operation_receipts` → `orders` → `item_versions` → `items` → `assets` → `accounts`），
  **不删表、不删 `schema_migrations`**。
- 所以 `DB_URL` 必须指向一次性测试库；当前代码**没有库名白名单保护**，指错库会清空数据（已在审查中列为缺陷）。
- 故障注入用例会临时 `RENAME TABLE notifications ...` / `notification_outbox ...`，并在 `finally` 恢复；
  若进程被强杀，可能残留 `notifications_bak` / `notification_outbox_bak`。恢复命令：

```powershell
& "$mysqlBin\mysql.exe" -h 127.0.0.1 -P 3307 -u root campus_resale_selftest -e @"
RENAME TABLE notifications_bak TO notifications, notification_outbox_bak TO notification_outbox;
"@
```

（若只残留其中一张，按实际存在的那张单独执行 `RENAME TABLE <x>_bak TO <x>;` 即可。）

### 6.6 手工冒烟（可选：验证运行态而非测试态）

```powershell
# 1) 重建运行库（只针对合成库）
& "$mysqlBin\mysql.exe" -h 127.0.0.1 -P 3307 -u root -e @"
DROP DATABASE IF EXISTS campus_resale_app;
CREATE DATABASE campus_resale_app CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
"@

# 2) 启动后端（首次启动自动建表并写 schema_migrations）
$env:DB_URL      = 'jdbc:mysql://127.0.0.1:3307/campus_resale_app?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&connectionTimeZone=UTC&serverTimezone=UTC'
$env:DB_USERNAME = 'root'
$env:DB_PASSWORD = ''
java -jar implementation\backend\target\campus-resale-backend.jar

# 3) 另开终端检查
curl.exe http://127.0.0.1:18080/api/health   # 200 {"status":"UP","database":"MySQL","schema":"V1","checkedAt":...}
curl.exe http://127.0.0.1:18080/api/items    # 200 []
curl.exe -i -X POST http://127.0.0.1:18080/api/items                      # 401 {"message":"请先登录"}
curl.exe -i http://127.0.0.1:18080/api/favorites                          # 404 {"message":"接口或资源不存在"}
```

重启一次后再看 `/api/health`：仍为 `schema=V1`、已有数据保留，日志不再出现「已执行结构迁移」。

### 6.7 停止与清理

```powershell
# 停止本次启动的后端（按命令行匹配，避免误杀其他 java 进程）
Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
    Where-Object { $_.CommandLine -like '*campus-resale-backend.jar*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }

# 停止一次性 MySQL 实例（不影响系统 3306 服务）
& "$mysqlBin\mysqladmin.exe" -h 127.0.0.1 -P 3307 -u root shutdown

# 清理构建产物（保留源码）
cd implementation\backend ; mvn -B clean

# 丢弃整台合成实例（可选）
# Remove-Item -Recurse -Force "$env:USERPROFILE\tools\mysql84-dev"
```

### 6.8 排错表（含本轮实际遇到的现象）

| 现象 | 原因 | 处理 |
|---|---|---|
| `Failed to determine a suitable driver class` | `DB_URL` 被设为空串/空白，覆盖了默认值 | `Remove-Item Env:DB_URL` 用默认值，或给完整 JDBC URL |
| `Access denied for user 'root'@'localhost'` | 连到了系统 3306 服务，或口令与该实例不符 | 确认 `-P 3307` 与 6.2 的实例账号，不要猜系统服务口令 |
| `Unknown database 'campus_resale_selftest'` | 未建测试库 | 执行 6.2 的 `CREATE DATABASE` |
| `Table ... notification_outbox doesn't exist` | 故障注入用例被中断，残留 `*_bak` | 按 6.5 的 `RENAME TABLE` 恢复后重跑 |
| `'mvn' 不是内部或外部命令` | Maven 未安装或不在 PATH | 设 `MAVEN_HOME`（6.4 第 0 步）或加入 PATH |
| `build.ps1` 报「字符串缺少终止符」或中文乱码 | 文件被重存成无 BOM 的 UTF-8，PS 5.1 按 ANSI 解析 | 用 `pwsh` 执行，或保持 UTF-8 with BOM |
| `Can't connect to MySQL server on '127.0.0.1:3307'` | 合成实例未启动 | 按 6.2 启动并 `mysqladmin ping` |
| 端口 3307 / 18080 被占用 | 已有其他实例 | 改实例端口并同步 `DB_URL`；应用端口用 `SERVER_PORT` |

### 6.9 与 D 独立验证的边界

本节只回答「怎么把环境跑起来」。**预期是否成立、独立脚本与反例设计、证据与阶段验收**属于 D 的职责；
A/B/C 自测通过不代替 D 的独立检查，也不代替四位实际成员的阶段确认。

## 7 自测结果与覆盖（2026-11-25 实跑）

`cd implementation/backend ; mvn -B test` → **43 tests / 0 failures**（MySQL 8.4.13 独立合成库）。

| 测试类 | 用例数 | 覆盖 | 对应 TC |
|---|---|---|---|
| `ItemLifecycleTest` | 9 | 发布校验无半记录、同键重放、旧版本审核、撤下与重发、占用期禁止改标的、公开脱敏与可见性、审计内容 | 04/05/06/07/22/24 |
| `OrderLifecycleTest` | 6 | 主链、买方本人确认、取消、争议与交付守卫、回执抗受限、下单前置条件 | 10/11/12/13/22/27 |
| `OrderConcurrencyTest` | 3 | 两买方并发、同意图并发回同一结果、取消 vs 交付登记竞争 | 08/11 |
| `NotificationFailureTest` | 2 | Outbox 写失败整体回滚；提交后投递失败可补投且不重跑业务 | 25/26 |
| `OutboxDeadLetterTest` | 1 | 达重试上限转 DEAD：记录保留、停止重试、业务不撤销 | 25/26 |
| `AssetUploadTest` | 6 | VERIFY 未认证可传、ITEM 需认证、伪类型/空/超限拒绝、私有图匿名 403、公开图匿名可读 | 24（资源授权） |
| `ApiContractTest` | 10 | 健康检查、匿名列表、401/400/404 错误体、缺上传字段、snake_case、过时版本 | 24/28 |
| `DefaultActorContextTest` | 1 | 未接入会话实现时默认 401（回归冒烟缺陷） | 03/24 |
| `MigrationAndRulesTest` | 5 | 迁移可重复、健康检查版本、分号切分、价格与脱敏规则 | 02/04/24/28 |

真实启动冒烟（同日）：`/api/health`=200 `schema=V1`；匿名 `/api/items`=200 `[]`；无会话 `POST /api/items`=401；
未实现接口 `/api/favorites`=404（统一错误体，不用 500 冒充）；合成物品公开列表脱敏并返回公开卖方引用；
重启后数据保留、迁移不重复执行。

> 冒烟中发现并修复：Spring 组件扫描把共享契约 `ActorProvider` 误注册为 MyBatis Mapper 代理（`BindingException`），
> 改为只在 Mapper 接口上使用 `@Mapper`，并新增 `DefaultActorContextTest` 防回归。

## 8 待确认与风险（需相关成员决定）

| 编号 | 内容 | 对象 | 影响 |
|---|---|---|---|
| B-01 | `accounts` 放入 B 维护的共享迁移，C 用新增迁移扩展凭据/会话/认证；是否需要改为 C 提供首个身份迁移 | C | 迁移序列与身份表归属 |
| B-02 | 公开引用字段统一用 `accounts.public_ref`（Q-RB-04 建议），`/api/me` 的 id 口径需 C 配合 | C/A | 公开 DTO 与前端关联 |
| B-03 | 编辑即使只改非关键字段也产生新版本并重新待审（当前实现）；若需「非关键修改不重审」需先定字段范围 | 全组 | BR04 解释与 TC05 |
| B-04 | 「提醒」当前按订单一次（登记后未回应），多次提醒策略由 V2 与 BR19 细化 | 全组 | TC10/12 预期 |
| B-05 | 业务条件失败统一 400、越权 403、竞争 409、未登录 401；与原契约差异以 Q-RB-02 结论为准 | A/C/D | 前端提示与测试断言 |
| B-06 | `GET /api/items` 每页 20、无总数（候选兼容方案）；是否需要 total 待调用方确认 | A/D | 分页契约 |
| B-07 | 图片校验为 JPEG/PNG、≤5MB、≤1600 万像素（候选值），需接口评审锁定最终值 | 全组 | 上传边界与 TC |
| B-08 | `GET /api/assets/{id}` 的私有资源匿名访问统一返回 403（按 OpenAPI 已列响应）；若前端需要「请先登录」提示，需契约同时列出 401 | A/C/D | 资源读取错误分支 |
| B-09 | 幂等重放返回「对象当前状态」而非该次操作时的快照（订单动作、物品编辑）；是否改为返回操作时快照需与 A/C 确认 | A/C | 重试响应语义 |
| B-10 | 资产授权查询缺 `item_versions.image_id` / `orders.image_id` 索引，规模上升后需新增迁移（V2），本版未加 | B/D | 性能与迁移序列 |
| B-11 | 下单新增「卖方必须已认证」校验；若 C 的认证可被撤销/失效，需同步规则、接口与 TC | C/D | 资格口径 |

## 9 影响清单（供 D 检查）

- 新增：`implementation/backend/**`（pom、V1 迁移、业务包、43 个自测）、`implementation/build.ps1`、根 `.gitignore`；
  修改：`implementation/README.md`、`implementation/backend/README.md`（新增第 6 节可运行测试环境与启动方法）。
- 未改 `documents/` 中的原需求、Q/ADR、接口与测试预期；若上表 B-01—B-11 的结论与本实现不同，
  需同步 OpenAPI/数据设计与 TC 后再改代码。
- 未创建任何远端提交、未操作服务器或他人的数据库实例；所有验证只使用本机独立合成库。

## 10 缺陷修复记录（2026-11-25，对应代码审查结论）

修复后全量自测：**43 tests / 0 failures**（原 30），新增用例覆盖下列 F-01～F-16。

| 编号 | 审查发现的问题 | 修复方式 | 回归用例 |
|---|---|---|---|
| F-01 | 未认证用户不能上传 `VERIFY`，认证流程死锁 | `AssetService.upload` 按用途区分：VERIFY 任意登录且不受限即可，ITEM 要求已认证 | `AssetUploadTest` |
| F-02 | 缺表单字段/超限/非 multipart 返回 500；5MB 文件被请求体上限挤掉 | 新增 `MissingServletRequestPart`/`MaxUploadSize`/`Multipart` 处理；`max-request-size` 独立配置（6MB） | `ApiContractTest.uploadMissingPart`、`AssetUploadTest.rejectInvalidUploads` |
| F-03 | `page` 过大整数溢出 → 负 OFFSET → 500 | `page` 上限 5000，offset 用 `long` | `ItemLifecycleTest.publicSearchRespectsVisibility` |
| F-04 | `INSERT IGNORE` 吞掉真实错误后仍标记已发送 | 改为普通 INSERT，先按 `outbox_id` 查重再标记 | `NotificationFailureTest` |
| F-05 | 重试 50 次后静默永久放弃、无告警 | 上限可配置（默认 100），超限转 `DEAD` 并记 ERROR 日志，记录保留 | `OutboxDeadLetterTest` |
| F-06 | `GET_LOCK` 返回值未校验，多实例迁移保护不成立 | 校验返回值 1/0/NULL，未取得锁即停止启动 | 代码审查 |
| F-07 | 下单未校验卖方「已认证」 | `seller.isVerified()` 校验，未认证返回 400 | `OrderLifecycleTest.createPreconditions` |
| F-08 | 自测可能清空非测试库 | `SELECT DATABASE()` 断言含 `selftest`，确需他库须显式设 `CAMPUS_ALLOW_NON_TEST_DB=true` | 全部用例运行于 `campus_resale_selftest` |
| F-09 | 404 用例绑定 V2 路径 `/api/favorites`，V2 后必假失败 | 改为不存在的 `/api/__not_exists__` | `ApiContractTest` |
| F-10 | 私有图片匿名读 401，与 OpenAPI 只列 403/404 不符 | 统一 403（契约响应范围内），前端如需「请先登录」见 B-08 | `AssetUploadTest.privateAndPublicRead` |
| F-11 | 全行 UPDATE 会覆盖治理列的 `hidden/relist_allowed/disputed` | 拆成列级语句：B 只写 `status/current_version/image_id/updated_at`、`stage/reason/completed_at/reminded` | 全量回归 + 代码审查 |
| F-12 | 管理员可审核本人发布的物品 | 自审返回 403 | `ItemLifecycleTest.adminCannotReviewOwnItem` |
| F-13 | 任一用户读通知会触发全库补投 | 只补投当前收件人的待发送记录 | `NotificationFailureTest` |
| F-14 | 同键并发返回 409/400 而非回执 | 锁后重读回执，返回先成立请求的同一结果 | `OrderConcurrencyTest.sameIntentRace` |
| F-15 | 通知内容截断可能切断代理对导致写库失败 | 按 UTF-16 安全截断 | 代码审查 |
| F-16 | 请求体必填与 OpenAPI 不一致；缺 Content-Type 落 500 | 服务层对缺 body 返回 400（先判登录 401），新增「内容类型不支持」→400 | `ApiContractTest.publishRequiresBody`、`publishRequiresSession` |
| F-17 | 未使用的查询方法（死代码） | 移除 `OrderMapper.countByItem`、`OrderStages.isTerminal`、`OutboxMapper.findRetryable` | 编译 + 全量测试 |

**本轮未改、仍需决定的事项**（见 §8）：B-08（资源读取 401/403 的前端语义）、B-09（幂等重放返回当前状态还是操作时快照）、
B-10（资产查询索引，需新增迁移）、B-11（认证撤销后的资格口径）。这些涉及接口或数据契约，需 A/C/D 结论后再改。


