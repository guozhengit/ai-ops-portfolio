package model.router.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 预算护栏单元语义（V2-D2.3）：前置判定、已知/估记分桶、日切恢复、拒留痕。
 * Clock 注入推时钟，不 sleep。
 */
class BudgetGuardTest {

  @TempDir
  Path tempDir;

  private static final Instant T0 = Instant.parse("2026-10-31T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

  @Test
  void knownCostAccumulatesAndPreCheckDeniesPastBudget() throws Exception {
    Path events = tempDir.resolve("budget-events.jsonl");
    // 日预算 1.0，本笔预留 0.1：花到 0.95 后，0.95+0.1>1.0 应拒
    BudgetGuard guard = new BudgetGuard(true, 1.0, 0.1, 0, events, CLOCK);

    assertTrue(guard.preCheck().allowed());
    guard.recordCall(0.95, 100, 200, 0);
    assertEquals(0.95, guard.preCheck().spentKnown(), 1e-9);

    BudgetGuard.Decision denied = guard.preCheck();
    assertFalse(denied.allowed(), "已用 0.95 + 预留 0.1 > 日预算 1.0 应拒");
    guard.logDenial(denied);

    List<String> lines = Files.readAllLines(events);
    assertEquals(1, lines.size(), "拒绝必须留痕（append-only）");
    assertTrue(lines.get(0).contains("budget_denied"));
    assertTrue(lines.get(0).contains("\"spentKnownUsd\":0.95"));
  }

  @Test
  void unknownCostIsEstimatedAtUpperBoundButNeverMixedIntoKnown() throws Exception {
    Path events = tempDir.resolve("budget-events.jsonl");
    // 未知成本：3M tokens × $2/MTok 上界 = $6 估记进 estimated，known 保持 0（账本层纪律不碰）
    BudgetGuard guard = new BudgetGuard(true, 10.0, 0, 0, events, CLOCK);

    guard.recordCall(null, 1_000_000, 2_000_000, 2.0);

    BudgetGuard.Decision d = guard.preCheck();
    assertEquals(0.0, d.spentKnown(), 1e-9, "未知成本绝不混进 known（账本要诚实）");
    assertEquals(6.0, d.spentEstimated(), 1e-9, "护栏层按上界估记（护栏要数字）");
    assertTrue(Files.readString(events).contains("cost_estimated"), "估记本身要留痕");
  }

  @Test
  void dailyBucketResetsNextDayWithoutAnyStateCleanup() throws Exception {
    // 可推时钟：0.95 花在今天，明天预算自然恢复——不需要任何清理动作
    BudgetGuard guard = new BudgetGuard(true, 1.0, 0.1, 0, tempDir.resolve("e.jsonl"),
        Clock.offset(CLOCK, Duration.ZERO));
    guard.recordCall(0.95, 1, 1, 0);
    assertFalse(guard.preCheck().allowed());

    Clock nextDay = Clock.fixed(T0.plus(Duration.ofHours(24)), ZoneOffset.UTC);
    BudgetGuard tomorrow = new BudgetGuard(true, 1.0, 0.1, 0, tempDir.resolve("e.jsonl"), nextDay);
    assertTrue(tomorrow.preCheck().allowed(), "日切后预算恢复");
  }

  @Test
  void disabledGuardAlwaysAllows() {
    BudgetGuard off = BudgetGuard.disabled();
    assertTrue(off.preCheck().allowed());
    off.recordCall(null, 1_000_000, 1_000_000, 100.0);
    assertTrue(off.preCheck().allowed(), "关闭状态不累积、不拒绝");
  }
}
