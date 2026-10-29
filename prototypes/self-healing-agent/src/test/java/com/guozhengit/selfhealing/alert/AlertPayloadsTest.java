package com.guozhengit.selfhealing.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AlertPayloadsTest {

    private static final String SAMPLE = """
            {
              "version": "4",
              "groupKey": "chaos-demo-memory",
              "status": "firing",
              "receiver": "self-healing-agent",
              "alerts": [
                {
                  "status": "firing",
                  "labels": {"alertname": "ChaosMemoryLeak", "job": "chaos-demo", "severity": "P1"},
                  "annotations": {"summary": "内存泄漏：RSS 超 200MB"},
                  "startsAt": "2026-10-29T05:30:00Z",
                  "endsAt": "0001-01-01T00:00:00Z",
                  "generatorURL": "http://prom:9091/graph",
                  "fingerprint": "f1e2d3c4b5a6"
                }
              ]
            }
            """;

    @Test
    void parsesAlertmanagerV2Payload() throws Exception {
        var payload = new ObjectMapper().readValue(SAMPLE, AlertPayloads.AlertPayload.class);
        assertEquals("firing", payload.status());
        assertEquals(1, payload.alerts().size());
        var alert = payload.alerts().getFirst();
        assertEquals("ChaosMemoryLeak", alert.alertName());
        assertEquals("f1e2d3c4b5a6", alert.fingerprint());
        assertEquals("P1", alert.labels().get("severity"));
    }

    @Test
    void dedupKeySeparatesStatus() throws Exception {
        var mapper = new ObjectMapper();
        var firing = mapper.readValue(SAMPLE, AlertPayloads.AlertPayload.class).alerts().getFirst();
        String resolvedJson = SAMPLE.replace("\"status\": \"firing\"", "\"status\": \"resolved\"");
        var resolved = mapper.readValue(resolvedJson, AlertPayloads.AlertPayload.class).alerts().getFirst();
        assertNotNull(firing.dedupKey());
        assertEquals(firing.dedupKey().split("\\|")[0], resolved.dedupKey().split("\\|")[0]);
        assertEquals("resolved", resolved.dedupKey().split("\\|")[1]);
    }
}
