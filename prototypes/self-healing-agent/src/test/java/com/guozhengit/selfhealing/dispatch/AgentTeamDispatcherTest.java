package com.guozhengit.selfhealing.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guozhengit.selfhealing.alert.AlertPayloads;
import com.guozhengit.selfhealing.approval.ActionExecutor;
import com.guozhengit.selfhealing.approval.ApprovalStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AgentTeamDispatcher 构造与报告落盘的轻量验证（不发真实模型调用）。 */
class AgentTeamDispatcherTest {

    @TempDir
    Path tempDir;

    private AlertPayloads.AlertItem sampleAlert() {
        return new AlertPayloads.AlertItem("firing",
                Map.of("alertname", "ChaosMemoryLeak", "severity", "P1"),
                Map.of("summary", "内存泄漏：RSS 超 200MB"),
                "2026-10-29T05:30:00Z", "", "", "testfp001");
    }

    private ApprovalStore store() throws Exception {
        return new ApprovalStore(tempDir.resolve("approvals.jsonl").toString(), new ObjectMapper());
    }

    private AgentTeamDispatcher dispatcher(String baseUrl, String prom, String chaos) throws Exception {
        return new AgentTeamDispatcher(
                "test-key", baseUrl, "test-model",
                2, prom, chaos,
                tempDir.resolve("reports").toString(), store(), new ActionExecutor(chaos),
                "", "", "", 2, 10);
    }

    @Test
    void constructsWithApiKey() throws Exception {
        assertNotNull(dispatcher("https://example.invalid/v3", "http://127.0.0.1:9091", "http://127.0.0.1:9400"));
    }

    @Test
    void rejectsBlankApiKey() {
        assertThrows(IllegalStateException.class,
                () -> new AgentTeamDispatcher(
                        "", "https://example.invalid/v3", "m",
                        2, "http://127.0.0.1:9091", "http://127.0.0.1:9400",
                        tempDir.resolve("reports").toString(), store(), new ActionExecutor("http://127.0.0.1:9400"),
                        "", "", "", 2, 10));
    }

    @Test
    void dispatchFailureIsContained() throws Exception {
        // 模型端点无效时 dispatch 不抛出（隔离在内部），也不写报告
        AgentTeamDispatcher d = dispatcher("http://127.0.0.1:1/v3", "http://127.0.0.1:1", "http://127.0.0.1:1");
        d.dispatch(sampleAlert(), "{}");
        Path reports = tempDir.resolve("reports");
        assertTrue(!Files.exists(reports) || listEmpty(reports), "失败诊断不应产出报告文件");
    }

    @Test
    void executorRejectsNonWhitelisted() {
        ActionExecutor ex = new ActionExecutor("http://127.0.0.1:9400");
        ActionExecutor.ExecResult r1 = ex.execute("rm -rf /");
        assertFalse(r1.ok(), "白名单外命令必须拒绝");
        ActionExecutor.ExecResult r2 = ex.execute("chaos:off:unknown-scene");
        assertFalse(r2.ok(), "未知场景必须拒绝");
        // 白名单内但目标不可达：不抛出、如实返回失败（确定性断言用不可达端口）
        ActionExecutor exDead = new ActionExecutor("http://127.0.0.1:1");
        ActionExecutor.ExecResult r3 = exDead.execute("chaos:off:slow-sql");
        assertFalse(r3.ok());
        assertTrue(r3.output().contains("执行异常") || r3.output().startsWith("HTTP"));
    }

    private boolean listEmpty(Path p) {
        try (var s = Files.list(p)) {
            return s.toList().isEmpty();
        } catch (Exception e) {
            return true;
        }
    }
}
