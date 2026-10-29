package com.guozhengit.selfhealing.alert;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * Alertmanager v2 webhook payload（只取我们需要的字段，多余字段忽略——
 * 原始 payload 另行整体落日志，证据链不丢信息）。
 */
public final class AlertPayloads {

    private AlertPayloads() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AlertPayload(
            String version,
            String status,
            String receiver,
            String groupKey,
            String externalURL,
            java.util.List<AlertItem> alerts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AlertItem(
            String status,
            Map<String, String> labels,
            Map<String, String> annotations,
            String startsAt,
            String endsAt,
            String generatorURL,
            String fingerprint) {

        public String alertName() {
            return labels == null ? "unknown" : labels.getOrDefault("alertname", "unknown");
        }

        /** 去重键：fingerprint + status（同一告警的 firing/resolved 是两个事件）。 */
        public String dedupKey() {
            String fp = (fingerprint == null || fingerprint.isBlank())
                    ? String.valueOf(alertName().hashCode()) : fingerprint;
            return fp + "|" + (status == null ? "unknown" : status);
        }
    }
}
