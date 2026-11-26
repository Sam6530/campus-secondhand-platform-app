# 校园循环 A成员 V1交付

本目录为2026-11-25按用户明确指示完成的A前端实现，任务关联TASK-V1-08。不是旧系统全量复制，也不是四人V1已经验收通过。

先读[运行说明](implementation/frontend/README.md)，再看[任务与交接](documents/A-V1/任务与交接.md)、[接口对接及协商事项](documents/A-V1/接口对接及协商事项.md)和[自测记录](documents/A-V1/自测记录.md)。

首次拉取：进入implementation/frontend，执行pnpm install --frozen-lockfile及pnpm build；之后双击启动前端.cmd，访问http://127.0.0.1:5173。开发调试使用pnpm dev。Git不提交node_modules和dist。

## 内容

- `implementation/frontend/`：Vue用户前端、公共组件、会话客户端、意图键及测试；已有本地构建自测记录，远端只提交源码与锁文件。
- `implementation/skills/`：新仓库本地快照中的当前分工，原样保留。
- `documents/`：新仓库评审稿及原需求的本地副本；原文不代填接受或签字。
- `documents/A-V1/`：本次任务、接口消费、来源、自测原件、截图、协商事项及交接。

没有B/C新后端，默认连接`http://127.0.0.1:18080`。没有后端时页面显示连接错误，生产代码不偷偷切换模拟数据。浏览器测试使用只在测试脚本中的API替身，不代表真实联调通过。C管理页有明确的组件接入位置，需C提供后再整合。

本批按用户2026-11-26指示使用v1-a作为A的V1工作分支，PR目标为v1。v1-a沿用当前v1基线并包含本次A前端成果，不自动合并，不操作服务器或真实数据库。全组技术准入、实际交叉审查和阶段验收记录仍由真实成员完成。
