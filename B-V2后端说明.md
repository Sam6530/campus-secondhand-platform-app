# B V2 后端功能与文件说明

日期：2026-11-26。工作分支：v2-b；PR目标：v2。后端位于implementation/backend，采用Java 17、Spring Boot 3.5.7、MyBatis与MySQL 8.4。

## 业务范围与接口

- 会话与账号：注册、登录、退出、当前用户查询、CSRF令牌下发与写请求校验、受限账户拦截。
- 商品：公开列表的分页、分类与关键词查询，商品详情、发布、编辑、撤下，审核状态与图片访问。
- 交易：下单、接单、拒单、取消、登记交付、确认收货、订单详情与列表，重复请求的操作标识与回执。
- 治理数据：举报、履约争议、补证、审计记录、站内通知与通知补投；管理侧读取和处置所需的查询与写入由C的接口承接。
- 数据结构迁移：V1基线、V2索引、V3幂等证据审计、V4并发更新保护、V5通知发件箱；启动时按版本顺序执行并记录校验值，已应用则跳过。
- 运行配套：统一配置、健康检查、上传目录、容器构建与本地启动参数。

上述内容描述后端接口与数据行为。页面入口由A提供，身份治理与聊天、管理接口由C提供；当前接口差异及V3核对事项见documents/B-V2/V3差异记录.md。

## 文件与材料

- src/main/java/cn/campus/下的13个B文件：MarketApplication、MarketService、Store、SqlMapper、SchemaMigrator、ApiError、WebConfig、HealthController、ItemController、OrderController、AssetController、ImageGuard、NotificationDelivery。MarketService保持整份，不拆分。
- src/test/java/cn/campus/下的7个测试：MarketIntegrationTest、BackendIssueRegressionTest、MarketMySqlIT、RootMySqlReviewIT、SchemaMigratorTest、StartupTest、TestStoreSpy。
- src/main/resources/application.yml：端口、会话、数据源、上传、迁移与初始管理员配置。
- src/main/resources/db/migration/：V1—V5五个脚本及说明。
- pom.xml：依赖、Java版本与可执行jar名称。
- README.md：模块说明、配置表、构建与测试方式。
- implementation/Dockerfile、compose.yml、.env.example、.dockerignore：容器与本地运行配套。
- documents/B-V2/：后端文件清单、V3差异记录及独立来源记录。

C负责的AdminBootstrap、AdminController、AuthController、CommunityController、SeedData、SessionSupport六个文件保持原样，不在本清单内。

## 使用入口

在implementation/backend执行mvn -B test运行测试，mvn -B -DskipTests package生成target/campus-resale-backend.jar；在implementation执行build.ps1可完成同一构建并校验jar。启动前通过环境变量提供DB_URL、DB_USERNAME、DB_PASSWORD；首次空库启动需BOOTSTRAP_ADMIN_USERNAME与BOOTSTRAP_ADMIN_PASSWORD，或仅在本地演示环境设置DEMO_SEED=true。默认端口18080，健康检查为GET /api/health。

运行参数与容器说明见implementation/backend/README.md及implementation/.env.example。本轮未构建容器镜像，未执行前端联调。

## 本轮核对与验证

- 2026-11-26执行mvn -B test：50个测试，0失败、0错误、0跳过，BUILD SUCCESS，用时13.7秒；测试使用H2内存库，异常堆栈为断言用例的预期输出。
- 同日执行mvn -B -DskipTests package：BUILD SUCCESS，生成campus-resale-backend.jar，大小26,702,667字节。
- 在仓库外的临时独立MySQL 8.4.13实例（127.0.0.1:3308，空库campus_v2_check）启动jar：V1—V5迁移全部应用，GET /api/health返回200且schema为V5，GET /api/items返回200与空列表，GET /api/me返回200并下发CSRF令牌，使用初始管理员登录返回200且会话可继续读取；再次启动时五个迁移均提示已应用并跳过，管理员不重复创建。
- 临时实例验证完成后已停止并删除临时数据目录；未连接或修改本机3306实例及既有数据库。
- 未执行MarketMySqlIT与RootMySqlReviewIT（未提供IT_DB_URL）、容器镜像构建、前端联调、浏览器端操作与阶段验收。

本次核对了33个后端及运行配套文件及其哈希。target、上传数据、本机配置、真实账号与密钥不包含在交付中。来源信息集中列在documents/B-V2/来源记录.md及.json。
