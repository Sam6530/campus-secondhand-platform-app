# A V2 前端功能与文件说明

日期：2026-11-26。工作分支：v2-a；PR目标：v2。前端位于implementation/frontend，采用Vue 3、Vite和JavaScript。

## 页面与交互

- 账号与认证：注册、登录、退出、校园认证材料提交及认证结果查看。
- 商品：分类搜索、分页、图片上传、发布、编辑、撤下与商品详情展示。
- 交易：下单、接单、拒单、取消、登记交付、确认收货、订单详情和提醒入口。
- 社区与沟通：收藏及取消收藏、成交评价、卖方评价查看、商品咨询和订单聊天。
- 治理与管理：举报、履约争议申请与补证；认证及商品审核、举报和争议处理、隐藏、账户限制恢复、统计页面。
- 公共体验：站内通知、表单校验、操作标识、账号切换时的状态清理、错误反馈及统一样式。

上述内容描述前端页面与调用入口。服务端权限、数据和交易结果由B/C接口提供；当前接口差异及后续验证事项见documents/A-V2/V3差异记录.md。

## 文件与材料

- src/App.vue：集中组织用户页面和管理页面。
- src/下的辅助JS：请求、操作标识、订单、会话、聊天输入、表单、统计及证据展示。
- public、index.html、src/style.css：图标、网页入口和样式。
- package.json、package-lock.json、vite.config.js：依赖、版本锁定和构建配置。
- test/：已有行为检查和静态检查脚本。
- documents/A-V2/：前端文件清单、V3差异记录及独立来源记录。

## 使用入口

在implementation/frontend执行npm ci安装固定依赖，npm run dev启动开发服务；npm run build生成dist，npm run preview预览构建结果。默认开发端口5173，API代理到127.0.0.1:18080。命令属于使用说明，本轮未重新执行安装、构建或业务测试。

行为检查脚本为node test/behavior.test.mjs，静态检查脚本为node test/static-check.mjs；package.json没有test脚本。运行与接口问题按V3差异记录跟进。

本次核对了18个前端文件及其哈希。node_modules、dist、本机私人配置和密钥不包含在交付中。来源信息集中列在documents/A-V2/来源记录.md及.json。
