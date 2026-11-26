# C 角色 V1 送审说明

本分支交付 V1 会话、注册登录、CSRF、身份认证及物品审核入口、物品与订单聊天、收件箱和管理页。用户端对接依据为 A-V1-GitHub上传包。管理页使用 A 的 client、user 和 changed 约定。订单详情由 B 提供；聊天读写在服务端独立核对订单参与人和阶段。

## 来源与集成

生产模块依赖 baseline-b 的 ActorProvider、ActorContext、ApiException、ItemReviewService 和 OutboxService。accounts、items、orders、audit_facts 和 assets 的定义来自 B 的 V1__trade_core.sql。共享后端构建文件基于 B 的 pom.xml，增加 H2 测试依赖。C 的 V2__identity_chat.sql 是共享迁移的第二个序号，属于产品 V1；合入前请 B 核对编号。管理页集成时采用 C 的 management.js 替换 A 的空入口。

## 可复验结果

运行 implementation/verification/c-contracts/test.ps1。脚本在系统临时目录组合 C 源文件、B 的身份/异常契约和审核/通知测试替身，使用 Maven、Spring MVC 与独立 H2 数据库。验证目录的类仅供隔离测试。正式后端使用 B 的真实组件。

2026-11-26 执行 9 项后端测试通过，覆盖会话身份、凭据、CSRF、HTTP 注册、认证版本与审核权限、物品审核委托、物品会话隔离及旧单终态。A 的上传包临时副本接入 C 管理页后通过生产构建和 6 项 A 原有客户端测试。上述前端结果证明编译与客户端回归；管理页真实浏览器操作仍待 A/C/D 联调。

## 集成复核

请 B/C 在阶段集成分支核对迁移顺序、真实 MySQL 事务与 Outbox 投递、物品审核调用，并用合成的管理员和用户身份走完整链路。D 独立复验并收集阶段结论。V2 举报、争议和治理任务按阶段准入记录推进。
