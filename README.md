# 校园二手交易与物品流转平台

本轮沿用原需求重新实施：V1、V2两版实现，V3系统测试。A/B/C实现并自测，D贯穿每版独立验证；各版四名实际成员对同一基线确认通过后进入下一版。工程文档默认全组共同完成，测试及验收材料由D主责。

开工前的文档已整理为一套评审稿。先从[工程文档入口](documents/README.md)阅读，再查看[架构讨论稿](documents/design/架构设计_讨论稿.md)、[阶段计划](documents/stages/阶段计划.md)及[18项任务](documents/stages/任务索引.md)。原需求原样保留；技术方案的评审记录与实施状态分开维护。

| 成员 | 技术职责 | Skill |
|---|---|---|
| A | 前端公共工程、用户交易和社区页面、集成自测 | [A](implementation/skills/campus-resale-a-frontend/SKILL.md) |
| B | 交易数据、迁移和事务并发；收藏、评价信誉、统计及通知后端 | [B](implementation/skills/campus-resale-b-transaction/SKILL.md) |
| C | 身份会话、认证审核、治理争议、聊天后端与管理页面 | [C](implementation/skills/campus-resale-c-governance/SKILL.md) |
| D | 每版独立测试、缺陷复验、测试文档和阶段验收材料 | [D](implementation/skills/campus-resale-d-verification/SKILL.md) |

项目文件直接放在implementation下；documents集中放需求、设计、阶段、测试及证据。[组织架构](documents/组织架构.md)列目录，[协作流程](documents/协作流程.md)说明main、v1/v2/v3与个人任务分支的合并方式。

初始化时把自己的Skill交给Agent，并说明：

> 我负责A（替换为自己的角色）。请读取本项目共同协作约定、对应Skill及工程文档入口。先核对当前版本和任务准入，给出与我相关的方案意见、依赖和需要协商的问句。文档供全组评审；进入实现时按已批准任务执行，并同步契约、自测和交接材料。

当前材料包含沿用的需求、开工前评审稿及四份Skill，后续业务代码直接在implementation内开发。实际运行说明由实现任务落实到implementation/README.md和启动脚本；GitHub各批实际操作仍须用户明确授权。
