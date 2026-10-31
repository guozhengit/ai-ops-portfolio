# 安全加固方案：AI-OPS 基础设施信任链（2026-10-29）

> 定位：方案文档，**未执行**。本文基于 2026-10-29 chaos-demo 上 160 全过程实测暴露的攻击面整理。
> 执行原则：每项热变更、可回滚、先备份后改动、改完 API 回查验证（与 self-healing-agent 同一套纪律）。

## 一、信任链现状（证据）

2026-10-29 部署过程中实际使用/确认的通道与权限：

```
开发机（Windows, IP DHCP: .159→.16 已变）
 ├─ .env 明文 6 家云渠道密钥（gitignore 已挡入库，磁盘明文存在）
 ├─ SSH 免密 admin@192.168.1.160（BatchMode 可用，个人主 key）
 ├─ Docker Desktop（daemon 配 daocloud+1panel mirror，可 pull；push 被墙）
 └─ GitHub push 权限（guozhengit/ai-ops-portfolio）
      ↓ SSH
160（macOS Docker host，~20 容器）
 ├─ admin 高权限账号，可任意命令（本次全程用它）
 ├─ Prometheus :9091 完全无认证（本次 API 全部裸查成功）
 ├─ Alertmanager :9093、chaos-demo :9400 等端口内网全开放
 └─ 中间件：3306/6379/5672/18848/9200/6333…（部分已有 ACL/鉴权，ES/Qdrant 鉴权未做）
```

**信任根结论：开发机 = 全链单点。** 开发机沦陷 ⇒ .env 密钥泄露 + 160 任意命令执行 + 业务库全暴露。

## 二、威胁模型与防线总览

| 威胁类型 | 典型场景 | 核心防线 |
|---|---|---|
| 外部攻击者 | 内网横向（智能设备失陷→扫内网）、SSH 暴破、服务漏洞利用 | 网络白名单 + SSH 加固 + 服务鉴权 |
| Agent 误操作（手滑型） | rm 错目标、sed 改错文件、down 错容器 | 备份前置 + 高危动作脚本化 + 配置 git 化 |
| Agent 幻觉/污染 | 编造验证结果、外部数据拼进 shell 命令（注入） | API 回查验证 + 数据不进 shell 纪律 |
| Agent 越权（膨胀型） | 顺手做了未授权的变更 | opsbot 最小权限 + 变更审批门（复用 D11 模式） |

## 三、分级执行方案

### P0（暴露面最裸的两块 + 制度止血）

**P0-1 160 网络来源白名单（macOS pf）**

- 现状证据：Prometheus 9091 无认证且内网全开放；3306/6379 等中间件端口内网任意可达。
- 动作：160 启用 pf（macOS 自带），规则要点——管理/中间件端口（3306/6379/5672/18848/9091/9093/9411/9400 等）仅允许开发机所在 `192.168.1.0/24` 中**已知设备 IP** 访问；`pass in quick on en0 from 192.168.1.16 to any` 式白名单 + 默认 deny 这些端口；Docker 发布端口同步收紧。
- 注意：开发机 IP 是 DHCP，白名单要按 IP 段或给开发机做路由器 DHCP 静态租约（推荐后者，一并解决 IP 漂移问题）。
- 验证：从开发机 `curl 9091` 通；从内网其他设备 `curl 9091` 超时；业务容器间 devnet 内互访不受影响（pf 作用于宿主入口，docker bridge 流量不过 pf——需实测确认，若误伤则放行 docker 网段）。
- 回滚：`pfctl -d` 一键关闭 + 规则文件备份。

**P0-2 160 sshd 加固**

- 现状证据：admin 免密可用（含密码登录与否未实测，默认按开放处理）。
- 动作：`/etc/ssh/sshd_config`——`PasswordAuthentication no`、`PermitRootLogin no`、`AllowUsers admin`（后续加 opsbot）、可选 `AllowGroups`；重启 sshd（macOS：`launchctl` 或系统设置）。
- 验证：从开发机免密登录仍通；错误密码被拒。
- 回滚：sshd_config 备份恢复 + 重启 sshd。**注意：改之前确认至少两个可用入口（本机控制台/屏幕共享），防锁死。**

**P0-3 备份纪律制度化（零成本，立即生效）**

- 规则：**任何配置文件改动前必须 `cp <file> <file>.bak.<tag>-$(date +%Y%m%d-%H%M%S)`；没备份 = 没资格改。**
- 证据：10-29 两次线上配置改动（prometheus.yml / alertmanager.yml）均已按此执行（.bak.chaos-* 文件可查）——从「自觉」升格为「制度」，写进验证脚本规范与 MEMORY 约定。

### P1（最小权限 + 可回滚基建）

**P1-1 opsbot 专用账号 + 密钥分离**

- 动作：160 建 `opsbot` 账号（admin 组但 sudo 全禁或白名单化）；生成专用部署 key（可吊销），agent/自动化只用它；`authorized_keys` 用 `command=`/`restrict` 限定可执行命令集（`docker load|compose|logs`、固定运维脚本）。
- 收益：agent 出问题（含被提示词注入劫持的最坏情形）可秒级吊销 key，个人主 key 不陪葬。

**P1-2 高危动作脚本化**

- 动作：160 上 `/usr/local/lib/ops/` 建固定脚本集：`reload-prometheus.sh`、`reload-alertmanager.sh`、`chaos-load-image.sh` 等，无参数化（或仅极有限枚举参数）；opsbot 的 force_command 指向这些脚本。
- 收益：消除「拼接远程命令」这一注入面——SSH 只调名字，永不传逻辑。

**P1-3 160 关键配置 git 化**

- 动作：`analytics-stack/{prometheus,alertmanager}/` 真身纳入 git 仓库（本地 bare repo + 定期推 GitHub 私库均可），**线上文件以 git 为准**，改动 = commit + 部署脚本拉取 + reload；.bak 文件制度随之退役。
- 收益：diff 可见（改了什么一目了然）、秒级回滚（git checkout）、变更历史即审计日志。
- 与现有关系：repo 中 `ops/prometheus/prometheus.yml` 已是备份副本，差「线上以 git 为准」这半步。

**P1-4 Agent 变更审批门（复用 D11 模式）**

- 规则：跨主机基础设施变更（非 git 跟踪的）采用「Agent 提 diff 计划（改什么/影响面/回滚方式）→ 人工逐条批准 → 执行 → 回查验证」；单会话连改多文件的高节奏操作禁用。
- 收益：把「AI 值班员白名单+人守审批」的既有哲学，应用到基础设施通道本身，叙事闭环。

### P2（纵深防御）

| 项 | 动作 | 说明 |
|---|---|---|
| 镜像供应链 | 全部 compose 镜像 pin 到 digest；daocloud 源仅作 mirror 信任，构建产物（chaos-demo 等）推 Hub 后以自有镜像为准 | 与 Docker 栈可移植性治理合并做 |
| 160 磁盘快照 | Time Machine / APFS 快照定期化，或 clone 工具整机备份 | 兜底一切手滑 |
| 服务鉴权补课 | Prometheus 加 basic_auth（read-only 网段例外）、ES/Qdrant 鉴权（memory 已挂账） | 与 P0-1 白名单互补，白名单挡外、鉴权挡内 |
| 密钥治理收尾 | .env 全量轮换后，开发机侧考虑 secret manager（哪怕只是 `age` 加密存储）；DeepSeek 402 账户按需充值 | 长期项 |
| 远程审计 | 160 shell history 落盘 / auditd（macOS 用 `sandbox`/`log` 方案） | 与 P1-1 配套 |

## 四、与作品集叙事的关联

这套方案本身就是素材（文章③/④的候选段落）：

- **同一哲学的两个应用**：self-healing-agent 用「白名单 + 审批门」管 AI 的修复动作；本方案把同一套思想管到「AI 的基础设施操作通道」上——AI 时代的运维边界不是不给权限，是**权限跟着角色走、每步可审计、每步可回滚**。
- **信任根意识**：开发机即信任根，`.env`、SSH key、GitHub 权限三者就是企业内「工程师笔记本」的等价物——面试聊安全时这是现成的个人实践案例。
- **证据链齐全**：今天部署全过程（含 Dockerfile 漏文件、AM header 不兼容、pf 规则需实测 docker bridge 流量等坑）都是一手材料。

## 五、执行顺序与验收门

```
P0-3（制度，立即）→ P0-2 sshd（先备好第二入口）→ P0-1 pf 白名单（先在低峰验证 docker 流量不误伤）
→ P1-1/P1-2（opsbot+脚本化，一次做完）→ P1-3（git 化）→ P1-4（审批门流程化）
→ P2 按资源情况滚动
```

- 每项验收：改动前的备份文件存在 + 改动后 API/功能回查通过 + 回滚路径演练过（至少口述可行）。
- 全部完成前，本文档不视为关闭；执行记录追加到 `.workbuddy/memory/` 对应日期。

---
状态：v1 方案（2026-10-29）。执行进度：**P0-3 已制度化（2026-10-31，写入 MEMORY + verification README）**；
P0-2 / P0-1 runbook 已备（[security-p0-runbook.md](security-p0-runbook.md)，含备份/验证/三档回滚），
2026-10-31 实测 admin@160 免密可达（macOS 12.7.6）但 sudo 需密码 → sudo 步骤由机主按 runbook 亲自执行。
P1 / P2 未启动。
