package com.guozhengit.selfhealing.alert;

import com.guozhengit.selfhealing.dispatch.IncidentDispatcher;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alertmanager webhook 入口。
 *
 * <p>契约：必须快速返回 2xx（Alertmanager 对慢/失败投递会重试），
 * 重活全部转异步；令牌校验用常量时间比较，防时序侧信道。
 */
@RestController
@RequestMapping("/alerts")
public class AlertmanagerWebhookController {

    private static final Logger log = LoggerFactory.getLogger(AlertmanagerWebhookController.class);
    public static final String TOKEN_HEADER = "X-Webhook-Token";

    private final String expectedToken;
    private final DedupService dedup;
    private final IncidentJournal journal;
    private final IncidentDispatcher dispatcher;
    private final ExecutorService workerPool;

    public AlertmanagerWebhookController(
            @Value("${selfhealing.webhook.token:dev-token-change-me}") String expectedToken,
            DedupService dedup,
            IncidentJournal journal,
            IncidentDispatcher dispatcher,
            @Value("${selfhealing.worker.pool-size:4}") int poolSize) {
        if ("dev-token-change-me".equals(expectedToken)) {
            log.warn("webhook token 仍是开发默认值 —— 生产部署前必须通过环境变量 "
                    + "SELFHEALING_WEBHOOK_TOKEN 覆盖（.env 治理纪律）");
        }
        this.expectedToken = expectedToken;
        this.dedup = dedup;
        this.journal = journal;
        this.dispatcher = dispatcher;
        this.workerPool = Executors.newFixedThreadPool(poolSize);
    }

    @PostMapping("/webhook")
    public ResponseEntity<String> onWebhook(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody String rawBody) {

        if (token == null || !constantTimeEquals(token, expectedToken)) {
            journal.append("rejected", rawBody, "invalid token");
            log.warn("webhook rejected: invalid or missing {}", TOKEN_HEADER);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("unauthorized");
        }

        AlertPayloads.AlertPayload payload;
        try {
            payload = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(rawBody, AlertPayloads.AlertPayload.class);
        } catch (Exception e) {
            journal.append("rejected", rawBody, "unparseable payload: " + e.getMessage());
            return ResponseEntity.badRequest().body("invalid payload");
        }

        List<AlertPayloads.AlertItem> alerts = payload.alerts() == null ? List.of() : payload.alerts();
        int accepted = 0;
        int duplicated = 0;
        for (AlertPayloads.AlertItem alert : alerts) {
            boolean first = dedup.tryAccept(alert.dedupKey());
            if (first) {
                accepted++;
                try {
                    workerPool.execute(() -> dispatcher.dispatch(alert, rawBody));
                } catch (RejectedExecutionException e) {
                    log.error("dispatch queue full, dropping alert {}", alert.dedupKey());
                    journal.append("dropped", rawBody, "queue full: " + alert.dedupKey());
                }
            } else {
                duplicated++;
            }
        }

        String summary = "status=" + payload.status() + " accepted=" + accepted
                + " duplicated=" + duplicated + " total=" + alerts.size();
        journal.append(accepted > 0 ? "accepted" : "deduplicated", rawBody, summary);
        log.info("webhook: {}", summary);
        return ResponseEntity.ok("{\"accepted\":" + accepted + ",\"deduplicated\":" + duplicated + "}");
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
