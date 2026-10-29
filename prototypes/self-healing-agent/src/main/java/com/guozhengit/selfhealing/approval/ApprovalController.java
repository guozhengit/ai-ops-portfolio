package com.guozhengit.selfhealing.approval;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 人工审批闸门 REST API —— 「agent proposes, human disposes」的执行面。
 *
 * <p>流程：诊断产出动作 → 白名单外的进 pending 队列 → 人工 approve/reject
 * → approved 的动作由 ActionExecutor 执行并回显。全部流转有审计。
 */
@RestController
@RequestMapping("/approvals")
public class ApprovalController {

    private final ApprovalStore store;
    private final ActionExecutor executor;

    public ApprovalController(ApprovalStore store, ActionExecutor executor) {
        this.store = store;
        this.executor = executor;
    }

    public record Decision(String decision, String decidedBy) {
    }

    @GetMapping
    public List<ApprovalStore.ApprovalRequest> list(
            @RequestParam(value = "status", required = false) String status) {
        return store.list(status);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApprovalStore.ApprovalRequest> get(@PathVariable String id) {
        ApprovalStore.ApprovalRequest r = store.get(id);
        return r == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(r);
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable String id, @RequestBody Decision body) {
        return decideAndMaybeExecute(id, "approve", body);
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable String id, @RequestBody Decision body) {
        return decideAndMaybeExecute(id, "reject", body);
    }

    private ResponseEntity<?> decideAndMaybeExecute(String id, String decision, Decision body) {
        ApprovalStore.ApprovalRequest r = store.decide(id, decision, body == null ? null : body.decidedBy());
        if (r == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not found"));
        }
        if ("pending".equals(r.status)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "already decided", "status", r.status));
        }
        // 批准且带可执行通道 → 立即执行并回显（执行失败也如实记录）
        if ("approved".equals(r.status) && r.command != null && !r.command.isBlank()) {
            ActionExecutor.ExecResult result = executor.execute(r.command);
            store.markExecuted(r, result.output(), result.ok());
        }
        return ResponseEntity.ok(r);
    }
}
