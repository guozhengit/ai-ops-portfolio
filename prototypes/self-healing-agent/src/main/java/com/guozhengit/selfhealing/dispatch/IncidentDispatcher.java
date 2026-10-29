package com.guozhengit.selfhealing.dispatch;

import com.guozhengit.selfhealing.alert.AlertPayloads;

/**
 * 事件转派接口 —— D9 只落地入口，D10 换成 AgentScope harness Team 的实现
 * （诊断编排在同进程内触发）。
 */
public interface IncidentDispatcher {

    /**
     * @param alert    单条告警（已去重）
     * @param rawBody  原始 webhook 报文（证据链）
     */
    void dispatch(AlertPayloads.AlertItem alert, String rawBody);
}
