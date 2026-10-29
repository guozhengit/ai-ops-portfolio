package com.guozhengit.selfhealing.dispatch;

import com.guozhengit.selfhealing.alert.AlertPayloads;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void constructsWithApiKey() {
        AgentTeamDispatcher d = new AgentTeamDispatcher(
                "test-key", "https://example.invalid/v3", "test-model",
                2, "http://127.0.0.1:9091", "http://127.0.0.1:9400",
                tempDir.resolve("reports").toString());
        assertNotNull(d);
    }

    @Test
    void rejectsBlankApiKey() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new AgentTeamDispatcher(
                        "", "https://example.invalid/v3", "m",
                        2, "http://127.0.0.1:9091", "http://127.0.0.1:9400",
                        tempDir.resolve("reports").toString()));
    }

    @Test
    void dispatchFailureIsContained() {
        // 模型端点无效时 dispatch 不抛出（隔离在内部），也不写报告
        AgentTeamDispatcher d = new AgentTeamDispatcher(
                "test-key", "http://127.0.0.1:1/v3", "test-model",
                1, "http://127.0.0.1:1", "http://127.0.0.1:1",
                tempDir.resolve("reports").toString());
        d.dispatch(sampleAlert(), "{}");
        Path reports = tempDir.resolve("reports");
        assertTrue(!Files.exists(reports) || listEmpty(reports), "失败诊断不应产出报告文件");
    }

    private boolean listEmpty(Path p) {
        try (var s = Files.list(p)) {
            return s.toList().isEmpty();
        } catch (Exception e) {
            return true;
        }
    }
}
