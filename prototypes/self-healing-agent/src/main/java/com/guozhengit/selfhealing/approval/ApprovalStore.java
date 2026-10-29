package com.guozhengit.selfhealing.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 审批队列（内存态 + JSONL 审计日志）。
 *
 * <p>铁律：Agent 只 propose，执行必须人点头。每次状态流转都追加审计记录
 * （谁、何时、做了什么决定），审计文件只追加、不改写。
 */
@Component
public class ApprovalStore {

    private static final Logger log = LoggerFactory.getLogger(ApprovalStore.class);

    /** 审批请求。status 流转：pending → approved/rejected → executed/failed。 */
    public static class ApprovalRequest {
        public String id;
        public String incidentFingerprint;
        public String alertName;
        public String description;   // 动作描述（Agent 提出）
        public String risk;          // low / medium / high
        public String command;       // 可执行通道标识（白名单内才非空），如 chaos:off:memory-leak
        public volatile String status = "pending";
        public String createdAt = Instant.now().toString();
        public String decidedBy;
        public String decidedAt;
        public String executionResult;
        public String source = "agent";   // agent / manual
    }

    private final ConcurrentHashMap<String, ApprovalRequest> requests = new ConcurrentHashMap<>();
    private final Path auditPath;
    private final ObjectMapper mapper;

    public ApprovalStore(@Value("${selfhealing.audit.path:data/approvals.jsonl}") String auditPath,
                         ObjectMapper mapper) throws IOException {
        this.auditPath = Path.of(auditPath);
        this.mapper = mapper;
        Files.createDirectories(this.auditPath.getParent());
        if (!Files.exists(this.auditPath)) {
            Files.createFile(this.auditPath);
        }
    }

    public ApprovalRequest create(String fingerprint, String alertName, String description,
                                  String risk, String command) {
        ApprovalRequest r = new ApprovalRequest();
        r.id = "apr-" + Instant.now().toEpochMilli() + "-" + (requests.size() + 1);
        r.incidentFingerprint = fingerprint;
        r.alertName = alertName;
        r.description = description;
        r.risk = risk == null ? "unknown" : risk;
        r.command = command;
        requests.put(r.id, r);
        audit(r, "created");
        return r;
    }

    public List<ApprovalRequest> list(String statusFilter) {
        return requests.values().stream()
                .filter(r -> statusFilter == null || statusFilter.isBlank() || r.status.equals(statusFilter))
                .sorted((a, b) -> b.createdAt.compareTo(a.createdAt))
                .toList();
    }

    public ApprovalRequest get(String id) {
        return requests.get(id);
    }

    /** 审批决定。只有 pending 态可流转。 */
    public synchronized ApprovalRequest decide(String id, String decision, String decidedBy) {
        ApprovalRequest r = requests.get(id);
        if (r == null) return null;
        if (!"pending".equals(r.status)) return r;   // 幂等：非 pending 不动
        if ("approve".equals(decision)) r.status = "approved";
        else if ("reject".equals(decision)) r.status = "rejected";
        else return r;
        r.decidedBy = decidedBy == null || decidedBy.isBlank() ? "operator" : decidedBy;
        r.decidedAt = Instant.now().toString();
        audit(r, "decided:" + decision);
        return r;
    }

    public void markExecuted(ApprovalRequest r, String result, boolean ok) {
        r.status = ok ? "executed" : "failed";
        r.executionResult = result;
        audit(r, ok ? "executed" : "failed");
    }

    private void audit(ApprovalRequest r, String event) {
        try {
            Map<String, Object> entry = Map.of(
                    "at", Instant.now().toString(),
                    "event", event,
                    "id", r.id,
                    "incident", String.valueOf(r.incidentFingerprint),
                    "alert", String.valueOf(r.alertName),
                    "risk", String.valueOf(r.risk),
                    "command", r.command == null ? "" : r.command,
                    "status", r.status,
                    "decidedBy", r.decidedBy == null ? "" : r.decidedBy,
                    "description", String.valueOf(r.description));
            Files.writeString(auditPath, mapper.writeValueAsString(entry) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("approval audit append failed: {}", e.toString());
        }
    }
}
