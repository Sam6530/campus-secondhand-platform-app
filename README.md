# 校园二手平台重新实施初始化

本包采用既有需求、两版实现加一版系统测试。当前只建立四人Skill和协作输入，没有开始业务实现或执行阶段验收。

## 给各自AI的入口

| 成员 | 技术主责与过程责任 | Skill |
|---|---|---|
| A | 前端基础与交易页面；基线、阶段组织 | [A](implementation/skills/campus-resale-a-frontend/SKILL.md) |
| B | 交易后端、数据库与并发；架构、ADR、关键设计统合 | [B](implementation/skills/campus-resale-b-transaction/SKILL.md) |
| C | 认证与平台治理及对应页面；Q业务澄清组织 | [C](implementation/skills/campus-resale-c-governance/SKILL.md) |
| D | 沟通、通知、收藏评价及对应页面；验证组织与需求追踪 | [D](implementation/skills/campus-resale-d-community/SKILL.md) |

先让AI读自己的Skill及其公共必读文件。示例：

> 我负责A（替换为实际角色）。请读取implementation/skills下对应SKILL.md及其必读文件。先核对已有需求、当前阶段、我的范围与依赖，输出当前任务计划及需要找其他成员协商的问句。未提供明确实现任务时先准备任务，不自行开始写业务代码。

四个会话分别服务真实同学，不自动启动四个AI扮演同学。Skill保留项目相对路径，分发整个包，不单独拷走一个SKILL.md。不自动安装到个人全局Skill目录。

## 目录

- implementation/：实现工程；本轮只写skills，前后端代码由后续批准任务建立。
- documents/：需求输入、设计、阶段和测试等工程文档。
- [共同协作约定](implementation/skills/共同协作约定.md)：权限、角色、阶段门槛及文档流程。
- [文档基线索引](documents/README.md)：原需求来源及各文档状态。
- [阶段计划](documents/stages/阶段计划.md)、[阶段验收记录](documents/stages/阶段验收记录.md)。

所有目录和非MD文件使用英文名；Markdown文件可中文。当前提交仅包含Skill及必要配套文档，不表示实现或验收完成。
