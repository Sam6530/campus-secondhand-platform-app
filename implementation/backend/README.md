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

## 5 数据与迁移

- 迁移序列由 B 维护：`V1__trade_core.sql` 建立 `accounts`（共享核心，凭据/认证等由 C 新增迁移扩展）、
  `items`、`item_versions`、`orders`、`reservations`、`deliveries`、`operation_receipts`、
  `notification_outbox`、`notifications`、`audit_facts`、`assets`。
- `schema_migrations` 记录版本、脚本名、SHA-256、执行时间；已执行脚本被改动会**启动失败**，要求新增升级脚本。
- 迁移执行使用 MySQL 命名锁串行化，多实例同时启动不会重复建表。
- 金额 `DECIMAL(12,2)`（正数，最大 9999999999.99）；时间统一 UTC `DATETIME(3)`，响应 ISO-8601 `Z`。

## 6 自测（2026-11-25 实跑）

`cd implementation/backend ; mvn -B test` → **30 tests / 0 failures**（MySQL 8.4.13 独立合成库）。

| 测试类 | 覆盖 | 对应 TC |
|---|---|---|
| `ItemLifecycleTest` | 发布校验无半记录、同键重放、旧版本审核、撤下、公开脱敏与可见性 | 04/05/06/07/22/24 |
| `OrderLifecycleTest` | 主链、买方本人确认、取消、争议与交付守卫、回执抗受限、下单前置条件 | 10/11/12/13/22/27 |
| `OrderConcurrencyTest` | 两买方并发、同意图并发（真实连接 + 屏障） | 08 |
| `NotificationFailureTest` | Outbox 写失败整体回滚；提交后投递失败可补投且不重跑业务 | 25/26 |
| `ApiContractTest` | 健康检查、匿名列表、401/400 错误体、404 统一错误体、snake_case 字段、过时版本拒绝 | 24/28 |
| `DefaultActorContextTest` | 未接入会话实现时的默认 401（回归冒烟缺陷） | 03/24 |
| `MigrationAndRulesTest` | 迁移可重复、健康检查版本、分号切分、价格与脱敏规则 | 02/04/24/28 |

真实启动冒烟（同日）：`/api/health`=200 `schema=V1`；匿名 `/api/items`=200 `[]`；无会话 `POST /api/items`=401；
未实现接口 `/api/favorites`=404（统一错误体，不用 500 冒充）；合成物品公开列表脱敏并返回公开卖方引用；
重启后数据保留、迁移不重复执行。

> 冒烟中发现并修复：Spring 组件扫描把共享契约 `ActorProvider` 误注册为 MyBatis Mapper 代理（`BindingException`），
> 改为只在 Mapper 接口上使用 `@Mapper`，并新增 `DefaultActorContextTest` 防回归。

## 7 待确认与风险（需相关成员决定）

| 编号 | 内容 | 对象 | 影响 |
|---|---|---|---|
| B-01 | `accounts` 放入 B 维护的共享迁移，C 用新增迁移扩展凭据/会话/认证；是否需要改为 C 提供首个身份迁移 | C | 迁移序列与身份表归属 |
| B-02 | 公开引用字段统一用 `accounts.public_ref`（Q-RB-04 建议），`/api/me` 的 id 口径需 C 配合 | C/A | 公开 DTO 与前端关联 |
| B-03 | 编辑即使只改非关键字段也产生新版本并重新待审（当前实现）；若需「非关键修改不重审」需先定字段范围 | 全组 | BR04 解释与 TC05 |
| B-04 | 「提醒」当前按订单一次（登记后未回应），多次提醒策略由 V2 与 BR19 细化 | 全组 | TC10/12 预期 |
| B-05 | 业务条件失败统一 400、越权 403、竞争 409、未登录 401；与原契约差异以 Q-RB-02 结论为准 | A/C/D | 前端提示与测试断言 |
| B-06 | `GET /api/items` 每页 20、无总数（候选兼容方案）；是否需要 total 待调用方确认 | A/D | 分页契约 |
| B-07 | 图片校验为 JPEG/PNG、≤5MB、≤1600 万像素（候选值），需接口评审锁定最终值 | 全组 | 上传边界与 TC |

## 8 影响清单（供 D 检查）

- 新增：`implementation/backend/**`（pom、V1 迁移、业务包、30 个自测）、`implementation/build.ps1`、根 `.gitignore`；
  修改：`implementation/README.md`。
- 未改 `documents/` 中的原需求、Q/ADR、接口与测试预期；若上表 B-01—B-07 的结论与本实现不同，
  需同步 OpenAPI/数据设计与 TC 后再改代码。
- 未创建任何远端提交、未操作服务器或他人的数据库实例；所有验证只使用本机独立合成库。

- 种子数据与迁移分离，本版未附带种子；自测通过测试夹具生成合成角色与物品。
