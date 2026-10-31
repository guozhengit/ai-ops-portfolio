#!/bin/bash
# P0-2 补齐：SSH 账号白名单（160 / macOS 12.7.6）
# 运行方式（在开发机 Git Bash 里执行，-t 让 sudo 密码提示走你自己的终端）：
#   ssh -t admin@192.168.1.160 'bash -s' < ops/security/p0-sshd-allowusers.sh
# 前提：admin 是唯一需要 SSH 的账号；有第二入口（物理/屏幕共享）。
set -euo pipefail

# ── P0-3 纪律：先备份，没备份 = 没资格改 ──
TS=$(date +%Y%m%d-%H%M%S)
sudo cp /etc/ssh/sshd_config /etc/ssh/sshd_config.bak.p0-$TS
echo "[1/4] 备份完成: /etc/ssh/sshd_config.bak.p0-$TS"

# ── drop-in 编号 300：排在现有 200-hardening 之后，不碰旧文件 ──
sudo tee /etc/ssh/sshd_config.d/300-p0-allowusers.conf > /dev/null <<'EOF'
# P0-2 补齐（2026-10-31）：SSH 账号白名单，后续加 opsbot 时在此追加
AllowUsers admin
EOF
echo "[2/4] 已写入 /etc/ssh/sshd_config.d/300-p0-allowusers.conf"

# ── 语法检查：不过就撤掉新文件，绝不带病重启 ──
if sudo /usr/sbin/sshd -t 2>&1; then
  echo "[3/4] SYNTAX-OK"
else
  echo "[3/4] SYNTAX-FAIL —— 已移除新 drop-in，未重启 sshd，一切保持原状"
  sudo rm /etc/ssh/sshd_config.d/300-p0-allowusers.conf
  exit 1
fi

# ── 重启 sshd 服务（只重启服务进程，机器不重启，已有会话不掉）──
sudo launchctl kickstart -k system/com.openssh.sshd
echo "[4/4] sshd 已重启。保持本会话在线作为保险通道。"
