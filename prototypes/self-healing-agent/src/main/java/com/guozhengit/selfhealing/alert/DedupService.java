package com.guozhengit.selfhealing.alert;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 告警去重：同一 fingerprint+status 在 TTL 窗口内只受理一次。
 *
 * <p>Alertmanager 会按 repeat_interval 周期性重发，Agent 不应被重复触发诊断。
 * resolved 与 firing 是不同事件，各自独立去重。
 */
@Component
public class DedupService {

    private final Map<String, Instant> seen = new ConcurrentHashMap<>();
    private final Duration ttl;

    public DedupService(@Value("${selfhealing.dedup.ttl-minutes:60}") long ttlMinutes) {
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    /** @return true = 首次受理；false = TTL 窗口内的重复通知。 */
    public boolean tryAccept(String dedupKey) {
        Instant now = Instant.now();
        evict(now);
        Instant prev = seen.putIfAbsent(dedupKey, now);
        return prev == null;
    }

    /** 仅测试与运维排查用。 */
    public void clear() {
        seen.clear();
    }

    private void evict(Instant now) {
        seen.values().removeIf(t -> t.plus(ttl).isBefore(now));
    }
}
