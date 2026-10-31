"""usage-report.py —— 成本账聚合（原型 2 / W3 验收门：成本数据有出处）。

用法：
    python ops/usage-report.py                 # 全部
    python ops/usage-report.py --days 7        # 只看最近 7 天
    python ops/usage-report.py --json          # 同时写 data/usage-report.json

读 prototypes/model-router/data/usage.jsonl（路由网关每次成功调用追加一行），
按「天 / 渠道 / 模型」三个维度聚合。

纪律（和落盘端一致，别在聚合这步把它破坏掉）：
- 价目未知的调用（costUsd=null / costKnown=false）**单独计数**，既不进总额也不当 0；
  把它们混进总额里等于把「不知道」算成「免费」。
- 旧格式的行没有 costKnown 字段，只有当 costUsd 是数字时才算已知成本。
"""

import argparse
import json
import math
import statistics
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path

DEFAULT_PATH = Path(__file__).resolve().parent.parent / "prototypes" / "model-router" / "data" / "usage.jsonl"
DEFAULT_JSON_OUT = Path(__file__).resolve().parent.parent / "prototypes" / "model-router" / "data" / "usage-report.json"


def load_rows(path):
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError:
            continue  # 坏行跳过，但要在汇总里说出来
    return rows


def parse_ts(row):
    raw = row.get("ts") or ""
    try:
        return datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        return None


def cost_of(row):
    """返回 (是否已知, 金额)。已知才返回金额，未知一律 0 且标记 False。"""
    known_flag = row.get("costKnown")
    value = row.get("costUsd")
    if known_flag is True and isinstance(value, (int, float)):
        return True, float(value)
    # 旧格式兼容：没有 costKnown 字段，但有数字金额
    if known_flag is None and isinstance(value, (int, float)):
        return True, float(value)
    return False, 0.0


class Bucket:
    def __init__(self):
        self.calls = 0
        self.prompt = 0
        self.completion = 0
        self.cost = 0.0
        self.unknown_cost_calls = 0
        self.latencies = []
        self.stream_calls = 0

    def add(self, row):
        self.calls += 1
        self.prompt += row.get("promptTokens") or 0
        self.completion += row.get("completionTokens") or 0
        known, amount = cost_of(row)
        if known:
            self.cost += amount
        else:
            self.unknown_cost_calls += 1
        latency = row.get("latencyMs")
        if isinstance(latency, (int, float)) and latency > 0:
            self.latencies.append(latency)
        if row.get("stream"):
            self.stream_calls += 1

    def as_dict(self):
        latencies = sorted(self.latencies)
        # 样本少时不能用「第 95% 个位置」这种插值思路：5 个样本的 P95 就该接近最大值
        p95_index = max(0, math.ceil(0.95 * len(latencies)) - 1) if latencies else None
        return {
            "calls": self.calls,
            "promptTokens": self.prompt,
            "completionTokens": self.completion,
            "costUsd": round(self.cost, 6),
            "unknownCostCalls": self.unknown_cost_calls,
            "streamCalls": self.stream_calls,
            "latencyAvgMs": round(statistics.mean(latencies), 1) if latencies else None,
            "latencyP95Ms": round(latencies[p95_index], 1) if latencies else None,
            "latencyMaxMs": round(max(latencies), 1) if latencies else None,
        }


def aggregate(rows, key_func):
    buckets = defaultdict(Bucket)
    for row in rows:
        buckets[key_func(row)].add(row)
    return {key: bucket.as_dict() for key, bucket in sorted(buckets.items())}


def print_table(title, data, total):
    print(f"\n== {title} ==")
    print(f"{'维度':<28}{'调用':>5}{'入 tok':>9}{'出 tok':>9}{'成本 USD':>12}{'未知成本':>9}{'均延迟':>9}{'P95':>8}")
    print("-" * 91)
    for key, item in data.items():
        avg = item["latencyAvgMs"]
        p95 = item["latencyP95Ms"]
        print(f"{key:<28}{item['calls']:>5}{item['promptTokens']:>9}{item['completionTokens']:>9}"
              f"{item['costUsd']:>12.6f}{item['unknownCostCalls']:>9}"
              f"{(f'{avg:.0f}ms' if avg else '-'):>9}{(f'{p95:.0f}ms' if p95 else '-'):>8}")
    if total:
        print("-" * 91)
        print(f"{'合计':<28}{total['calls']:>5}{total['promptTokens']:>9}{total['completionTokens']:>9}"
              f"{total['costUsd']:>12.6f}{total['unknownCostCalls']:>9}")


def main():
    parser = argparse.ArgumentParser(description="model-router 成本账聚合")
    parser.add_argument("--path", type=Path, default=DEFAULT_PATH, help="usage.jsonl 路径")
    parser.add_argument("--days", type=int, default=0, help="只看最近 N 天（0 = 全部）")
    parser.add_argument("--json", action="store_true", help="同时写 JSON 报告")
    parser.add_argument("--json-out", type=Path, default=None,
                        help="JSON 报告输出路径（默认 data/usage-report.json；证据重放门禁传临时目录，避免覆盖入库原件）")
    args = parser.parse_args()

    if not args.path.exists():
        print(f"账本不存在：{args.path}")
        return 1

    raw_lines = sum(1 for line in args.path.read_text(encoding="utf-8").splitlines() if line.strip())
    rows = load_rows(args.path)
    bad_lines = raw_lines - len(rows)

    if args.days > 0:
        cutoff = datetime.now(timezone.utc) - timedelta(days=args.days)
        rows = [row for row in rows if (parse_ts(row) or cutoff) >= cutoff]

    if not rows:
        print(f"账本里没有符合条件的记录（共 {raw_lines} 行）")
        return 0

    total = Bucket()
    for row in rows:
        total.add(row)

    by_day = aggregate(rows, lambda row: (parse_ts(row).astimezone().date().isoformat()
                                          if parse_ts(row) else "unknown"))
    by_channel = aggregate(rows, lambda row: row.get("channel") or "-")
    by_model = aggregate(rows, lambda row: row.get("model") or "-")

    stamp = ""
    if rows:
        stamps = [parse_ts(row) for row in rows if parse_ts(row)]
        if stamps:
            stamp = f"{min(stamps).astimezone():%Y-%m-%d %H:%M} ~ {max(stamps).astimezone():%Y-%m-%d %H:%M}"

    print(f"账本：{args.path}")
    print(f"记录：{len(rows)} 行（文件 {raw_lines} 行，坏行 {bad_lines}）  区间：{stamp}")

    print_table("按天", by_day, total.as_dict())
    print_table("按渠道", by_channel, total.as_dict())
    print_table("按模型", by_model, total.as_dict())

    unknown = total.unknown_cost_calls
    if unknown:
        print(f"\n⚠️ 有 {unknown} 笔调用价目未知（中转渠道无公开价目），已排除在总额之外，"
              f"不是 0 美元——是「不知道」。")
    print(f"\n已知成本合计：${total.cost:.6f}（{total.calls - unknown}/{total.calls} 笔有价目）")

    if args.json:
        report = {
            "source": str(args.path),
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "rows": len(rows),
            "badLines": bad_lines,
            "total": total.as_dict(),
            "byDay": by_day,
            "byChannel": by_channel,
            "byModel": by_model,
        }
        json_out = args.json_out or DEFAULT_JSON_OUT
        json_out.parent.mkdir(parents=True, exist_ok=True)
        json_out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"JSON 报告已写：{json_out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
