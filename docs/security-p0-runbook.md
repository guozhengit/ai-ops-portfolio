# P0 执行 Runbook：160 信任链加固（2026-10-31）

> 对应方案：[security-hardening-plan.md](security-hardening-plan.md) P0-1/P0-2/P0-3。
> 实测前提（2026-10-31 探测）：`ssh admin@192.168.1.160` 免密可达，macOS 12.7.6；
> **admin 的 sudo 需要密码** → 所有 `sudo` 步骤由机主在 160 上亲自执行（密码不进任何会话记录）。
> 每一步：**先备份 → 改 → 双入口验证 → 确认回滚路径**。顺序按方案：P0-2 先于 P0-1。

---

## P0-3 备份纪律制度化 ✅（2026-10-31 已执行，零成本）

规则原文：**任何配置文件改动前必须 `cp <file> <file>.bak.<tag>-$(date +%Y%m%d-%H%M%S)`；没备份 = 没资格改。**

- 已写进 `.workbuddy/memory/MEMORY.md`（AI 会话约定）
- 已写进 `verification/README.md` 设计原则（仓库规范层）
- 本 runbook 以下每步的第一条命令就是这条纪律的实例

---

## P0-2 sshd 加固（先做：风险低、收益立得）

### 第 0 步：确认第二入口（不做完不许继续）

160 是 macOS。确认**任选其一**成立：

- [ ] 160 接了键盘显示器，能物理登录 admin
- [ ] 屏幕共享/VNC 已开启（系统设置 → 通用 → 共享 → 屏幕共享）
- [ ] 还有另一个可 SSH 的账号且知道其密码

### 第 1 步：备份（在 160 上执行）

```bash
sudo cp /etc/ssh/sshd_config /etc/ssh/sshd_config.bak.p0-$(date +%Y%m%d-%H%M%S)
sudo sshd -T | grep -Ei 'passwordauthentication|permitrootlogin|allowusers|allowgroups' | tee ~/sshd-effective.bak.p0.txt
```

### 第 2 步：确认 sshd_config 是否支持 drop-in

```bash
sudo grep -i '^Include' /etc/ssh/sshd_config
```

- **有输出**（如 `Include /etc/ssh/sshd_config.d/*`）→ 用第 3A 步
- **无输出** → 用第 3B 步

### 第 3A 步：drop-in 方式（推荐，污染最小）

```bash
sudo tee /etc/ssh/sshd_config.d/100-p0-hardening.conf > /dev/null <<'EOF'
PasswordAuthentication no
PermitRootLogin no
AllowUsers admin
EOF
```

### 第 3B 步：直改主文件方式

```bash
sudo tee -a /etc/ssh/sshd_config > /dev/null <<'EOF'
# ── P0 hardening 2026-10-31（回滚见同目录 .bak.p0-*）──
PasswordAuthentication no
PermitRootLogin no
AllowUsers admin
EOF
```

### 第 4 步：重启 sshd 并保持当前会话不退出

```bash
sudo launchctl kickstart -k system/com.openssh.sshd
```

⚠️ **当前这个 SSH 会话保持不动**，所有验证从新窗口做。

### 第 5 步：双路验证（新开终端）

```bash
# ① 免密仍通（通过判定）
ssh -o BatchMode=yes admin@192.168.1.160 'echo key-ok'

# ② 密码登录被拒（通过判定：报 Permission denied [publickey]，不再出现 password 提示）
ssh -o PubkeyAuthentication=no -o PreferredAuthentications=password admin@192.168.1.160
```

两条都符合 → P0-2 完成。①不通或会话异常 → 立即回滚。

### 回滚（在仍活着的旧会话或 160 本机执行）

```bash
sudo ls -t /etc/ssh/sshd_config.bak.p0-* | head -1        # 找最新备份
sudo cp $(sudo ls -t /etc/ssh/sshd_config.bak.p0-* | head -1) /etc/ssh/sshd_config
sudo launchctl kickstart -k system/com.openssh.sshd
```

---

## P0-1 pf 管理端口白名单（后做：先验证 docker 流量不误伤）

### 第 0 步：前置条件

- [ ] P0-2 已完成且免密登录稳定
- [ ] 开发机 IP 已固定：路由器把 `192.168.1.16` 做 DHCP 静态租约（否则 pf 白名单会被 IP 漂移打破——这一步只能人工在路由器后台做）
- [ ] 低峰时段执行（方案要求）

### 第 1 步：观察管理端口现有来源（10 分钟，决定白名单内容）

```bash
sudo tcpdump -ni en0 -c 200 'port 9091 or port 3306 or port 6379 or port 9400' 2>/dev/null
```

记录出现的源 IP。预期只有开发机（.16）和容器网段（172.x，走 bridge 不过 en0，通常看不到）。

### 第 2 步：写锚点规则（备份 pf.conf 之后）

```bash
sudo cp /etc/pf.conf /etc/pf.conf.bak.p0-$(date +%Y%m%d-%H%M%S)

sudo tee /etc/pf.anchors/aiops-whitelist > /dev/null <<'EOF'
# ── AI-OPS P0-1：管理/中间件端口仅开发机可达（回滚：pfctl -d）──
table <aiops_admin> const { 192.168.1.16 }
block in log quick on en0 proto tcp from ! <aiops_admin> \
  to any port { 3306, 6379, 5672, 18848, 9091, 9093, 9400, 9411 }
EOF
```

### 第 3 步：把锚点挂进 pf.conf

编辑 `/etc/pf.conf`，在 `rdr-anchor "com.apple/*"` 一行**之后**插入两行：

```
anchor "aiops-whitelist"
load anchor "aiops-whitelist" from "/etc/pf.anchors/aiops-whitelist"
```

（手工编辑；`sudo sed -i '' '/rdr-anchor "com.apple\/\*"/a\
anchor "aiops-whitelist"\
load anchor "aiops-whitelist" from "/etc/pf.anchors/aiops-whitelist"
' /etc/pf.conf` 亦可。）

### 第 4 步：加载并验证

```bash
sudo pfctl -f /etc/pf.conf && echo LOAD-OK     # 语法错会在这一步报出来，不影响旧规则
sudo pfctl -s info | head -3                    # Status: Enabled
```

**三路验证（每路都要过）：**

```bash
# ① 开发机 → 管理端口：必须通
curl -m 3 -s http://192.168.1.160:9091/-/healthy && echo DEV-OK

# ② 内网其他设备（手机浏览器/另一台机器）→ :9091：必须超时

# ③ 容器互访不误伤（关键，方案点名要实测）：
ssh admin@192.168.1.160 'docker exec $(docker ps -q | head -1) sh -c "command -v curl >/dev/null && curl -sm 3 http://prometheus:9091/-/healthy || wget -qT 3 -O- http://prometheus:9091/-/healthy" 2>&1 | head -1'
```

若 ③ 被误伤 → docker bridge 流量确实过了 pf，在锚点文件**顶部**加一行放行后重新加载：

```
pass in quick on en0 proto tcp from 172.16.0.0/12 to any
```

### 第 5 步：回滚路径（三档，由轻到重）

```bash
# ① 只重载规则（改动锚点后）
sudo pfctl -f /etc/pf.conf
# ② 还原 pf.conf
sudo cp $(sudo ls -t /etc/pf.conf.bak.p0-* | head -1) /etc/pf.conf && sudo pfctl -f /etc/pf.conf
# ③ 总闸（一键全关，30 秒内恢复原状）
sudo pfctl -d
```

---

## 执行记录（完成后填写）

| 步骤 | 日期 | 执行人 | 验证结果 | 备份文件 |
| --- | --- | --- | --- | --- |
| P0-3 制度化 | 2026-10-31 | AI(代理) | 写入 MEMORY + verification README | — |
| P0-2 sshd | | | | |
| P0-1 pf | | | | |

完成后把本行状态改掉：`security-hardening-plan.md` 尾部状态行。
