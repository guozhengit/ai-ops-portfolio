package com.guozhengit.selfhealing.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 事件日志（JSONL 追加）—— 证据链的第一层落盘。
 *
 * <p>每条 = 接收时刻 + 原始 payload + 处理决定（accepted/deduplicated）。
 * Agent 的诊断报告之后要引用这里的时间线，所以只追加、不改写。
 */
@Component
public class IncidentJournal {

    private static final Logger log = LoggerFactory.getLogger(IncidentJournal.class);

    private final Path journalPath;
    private final ObjectMapper mapper;

    public IncidentJournal(@Value("${selfhealing.journal.path:data/incidents.jsonl}") String journalPath,
                           ObjectMapper mapper) throws IOException {
        this.journalPath = Path.of(journalPath);
        this.mapper = mapper;
        Files.createDirectories(this.journalPath.getParent());
        if (!Files.exists(this.journalPath)) {
            Files.createFile(this.journalPath);
        }
    }

    public synchronized void append(String decision, String rawBody, String summary) {
        try {
            var entry = new java.util.LinkedHashMap<String, Object>();
            entry.put("receivedAt", Instant.now().toString());
            entry.put("decision", decision);
            entry.put("summary", summary);
            entry.put("raw", mapper.readTree(rawBody));
            Files.writeString(journalPath, mapper.writeValueAsString(entry) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // 日志失败不能阻断告警处理，但必须留痕
            log.error("journal append failed: {}", e.toString());
        }
    }

    public Path path() {
        return journalPath;
    }
}
