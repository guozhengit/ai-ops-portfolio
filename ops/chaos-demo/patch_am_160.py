#!/usr/bin/env python3
"""向 160 的 alertmanager.yml 注入 Chaos.* 子路由 + selfhealing-agent webhook receiver（幂等）。"""
p = "/Users/admin/WorkBuddy/linux/analytics-stack/alertmanager/alertmanager.yml"
src = open(p).read()
if "selfhealing-agent" in src:
    print("ALREADY_PATCHED")
    raise SystemExit(0)

# 1) 子路由插到 routes: 列表最前（Chaos.* 优先于 null 兜底路由）
route_block = '''  routes:
    - receiver: "selfhealing-agent"
      matchers:
        - alertname =~ "Chaos.*"
      group_by: ["alertname", "fingerprint"]
      group_wait: 5s
      group_interval: 1m
      repeat_interval: 30m
'''
assert "  routes:\n" in src, "routes: anchor not found"
src = src.replace("  routes:\n", route_block, 1)

# 2) receiver 定义插到 "null" receiver 之后
recv_block = '''
  - name: "selfhealing-agent"
    webhook_configs:
      - url: "http://192.168.1.16:8400/alerts/webhook"
        send_resolved: false
        http_config:
          headers:
            X-Webhook-Token:
              - "dev-token-change-me"
'''
anchor = 'receivers:\n  - name: "null"\n'
assert anchor in src, "receivers anchor not found"
src = src.replace(anchor, anchor + recv_block, 1)

open(p, "w").write(src)
print("PATCHED")
