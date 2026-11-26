# 校园循环 V1 用户前端

Vue3、Vite、JavaScript。源代码无默认演示账号和业务mock入口。绿色样式参考旧系统；逻辑按新契约重新实现。后端接口及依赖边界见[对接说明](../../documents/A-V1/接口对接及协商事项.md)。

## 运行

已经有dist时，可直接从本目录执行`node serve.mjs`，或双击“最终/启动前端.cmd”。这是本地静态服务及/api代理，不是生产部署。API_PROXY_TARGET可在启动前作为环境变量设置。停止时在终端按Ctrl+C。

需要Node 20.19+（或支持Vite6的Node22），包管理使用pnpm；已提交pnpm-lock.yaml。GitHub源码不含node_modules和dist。首次拉取后安装依赖并执行pnpm build，再使用启动脚本；开发时使用pnpm dev。

```powershell
cd D:\aaaahomework\aaa3_1\软件工程\最终\implementation\frontend
pnpm install --frozen-lockfile
pnpm dev
```

访问http://127.0.0.1:5173 。API后台默认127.0.0.1:18080。可以复制.env.example为.env.local，设置API_PROXY_TARGET为B/C提供的后端地址，再重启Vite。浏览器通过同源/api，数据库密码不能填写到前端配置。

```powershell
pnpm test
pnpm build
pnpm preview
```

preview使用4173端口预览构建产物；它没有开发代理，API需要部署的同源反向代理才能使用。日常联调用pnpm dev。生产服务器应将/api与静态站点配置在同源入口，不以开发服务代替正式部署。

浏览器检查：先运行pnpm dev，另开终端安装或使用Playwright并执行`node tests/browser.cjs`。脚本默认使用本机Edge。可设置BROWSER_CHANNEL=chrome；PLAYWRIGHT_PACKAGE指向已有playwright包目录时无需另装测试依赖。测试会拦截API，仅测试前端，结果在documents/A-V1/evidence中。重放测试时使用合成页面和数据，不访问真实账户。

## 源码位置

- `src/App.vue`：用户页面集成、认证、会话及任务控制。
- `src/components/Market.vue`：搜索、列表和下单入口。
- `src/components/ItemEditor.vue`：物品发布编辑与上传。
- `src/components/Orders.vue`：身份、阶段按钮和订单快照入口。
- `src/components/Modal.vue`：公共对话框、焦点约束及键盘关闭。
- `src/lib/client.js`：请求、CSRF、超时、意图键、切号取消与过期响应隔离。
- `src/lib/rules.js`：页面可操作性提示，服务端仍必须最终授权。
- `src/integration/management.js`：C管理页接入入口。

提交失败保留表单；网络失败原样重试复用同次意图。浏览器刷新会清空内存意图与表单，结果不明时应先查询本人记录，不自动重放命令。当前只保留一个页面会话中的未决意图，刷新后的持久化重试体验作为已知限制交D核对。
