package com.guozhengit.selfhealing.alert;

import com.guozhengit.selfhealing.dispatch.IncidentDispatcher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WebhookIntegrationTest {

    static final String TOKEN = "test-token-123";
    static final CountingDispatcher COUNTING = new CountingDispatcher();

    @TempDir
    static Path tempDir;

    @Autowired
    MockMvc mvc;

    @Autowired
    DedupService dedup;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("selfhealing.webhook.token", () -> TOKEN);
        registry.add("selfhealing.journal.path", () -> tempDir.resolve("incidents.jsonl").toString());
        // 单测上下文不依赖真实模型 Key：诊断 Team 走 @Primary 计数替身
        registry.add("selfhealing.agent.enabled", () -> "false");
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        IncidentDispatcher countingDispatcher() {
            return COUNTING;
        }
    }

    static class CountingDispatcher implements IncidentDispatcher {
        final List<String> dispatched = new CopyOnWriteArrayList<>();

        @Override
        public void dispatch(AlertPayloads.AlertItem alert, String rawBody) {
            dispatched.add(alert.dedupKey());
        }
    }

    private static final String BODY = """
            {
              "version": "4", "status": "firing", "receiver": "self-healing-agent",
              "alerts": [{
                "status": "firing",
                "labels": {"alertname": "ChaosPoolExhausted", "job": "chaos-demo"},
                "fingerprint": "abc123"
              }]
            }
            """;

    @Test
    void rejectsMissingToken() throws Exception {
        mvc.perform(post("/alerts/webhook").content(BODY))
                .andExpect(status().isUnauthorized());
        assertEquals(0, COUNTING.dispatched.size());
    }

    @Test
    void rejectsWrongToken() throws Exception {
        mvc.perform(post("/alerts/webhook")
                        .header(AlertmanagerWebhookController.TOKEN_HEADER, "wrong")
                        .content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsAndDispatches() throws Exception {
        dedup.clear();
        COUNTING.dispatched.clear();
        mvc.perform(post("/alerts/webhook")
                        .header(AlertmanagerWebhookController.TOKEN_HEADER, TOKEN)
                        .content(BODY))
                .andExpect(status().isOk());
        // 异步转派，等一拍
        long deadline = System.currentTimeMillis() + 3000;
        while (COUNTING.dispatched.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(1, COUNTING.dispatched.size());
    }

    @Test
    void deduplicatesSameFingerprintStatus() throws Exception {
        dedup.clear();
        COUNTING.dispatched.clear();
        mvc.perform(post("/alerts/webhook")
                        .header(AlertmanagerWebhookController.TOKEN_HEADER, TOKEN)
                        .content(BODY))
                .andExpect(status().isOk());
        mvc.perform(post("/alerts/webhook")
                        .header(AlertmanagerWebhookController.TOKEN_HEADER, TOKEN)
                        .content(BODY))
                .andExpect(status().isOk());
        Thread.sleep(300);
        assertEquals(1, COUNTING.dispatched.size(), "TTL 窗口内重复通知不得重复转派");
    }

    @Test
    void journalRecordsEvidence() throws Exception {
        dedup.clear();
        COUNTING.dispatched.clear();
        mvc.perform(post("/alerts/webhook")
                        .header(AlertmanagerWebhookController.TOKEN_HEADER, TOKEN)
                        .content(BODY))
                .andExpect(status().isOk());
        Path journal = tempDir.resolve("incidents.jsonl");
        long deadline = System.currentTimeMillis() + 3000;
        while (Files.size(journal) == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        String content = Files.readString(journal);
        assertTrue(content.contains("\"decision\""), "journal 必须记录处理决定");
        assertTrue(content.contains("ChaosPoolExhausted"), "journal 必须保留原始 payload");
    }
}
