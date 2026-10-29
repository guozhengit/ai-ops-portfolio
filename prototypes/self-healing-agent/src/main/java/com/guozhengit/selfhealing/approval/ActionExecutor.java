package com.guozhengit.selfhealing.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * 白名单动作执行器（沙箱验证执行点的本机版）。
 *
 * <p>安全模型：command 必须命中白名单才执行，白名单外的动作一律拒绝——
 * 即使有人误批也不执行。当前白名单只有「解除 chaos-demo 故障注入」这一类
 * 低风险动作；真实 Docker 沙箱执行点（160）接入后按同一白名单机制扩展。
 */
@Component
public class ActionExecutor {

    public record ExecResult(boolean ok, String output) {
    }

    private static final Logger log = LoggerFactory.getLogger(ActionExecutor.class);
    private static final String PREFIX = "chaos:off:";

    private final String chaosBaseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public ActionExecutor(@Value("${selfhealing.chaos.base-url}") String chaosBaseUrl) {
        this.chaosBaseUrl = chaosBaseUrl;
    }

    /** 是否在白名单内（UI/API 可据此显示「可自动执行」）。 */
    public static boolean whitelisted(String command) {
        return command != null && command.startsWith(PREFIX)
                && List.of("memory-leak", "slow-sql", "disk-full", "pool-exhaustion", "nacos-flap")
                .contains(command.substring(PREFIX.length()));
    }

    public ExecResult execute(String command) {
        if (!whitelisted(command)) {
            log.warn("[executor] 拒绝白名单外命令: {}", command);
            return new ExecResult(false, "拒绝执行：命令不在白名单（" + command + "）");
        }
        String scene = command.substring(PREFIX.length());
        try {
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create(chaosBaseUrl + "/fault/" + scene + "/off"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(15)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new ExecResult(resp.statusCode() == 200,
                    "HTTP " + resp.statusCode() + " " + resp.body());
        } catch (Exception e) {
            return new ExecResult(false, "执行异常: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }
}
