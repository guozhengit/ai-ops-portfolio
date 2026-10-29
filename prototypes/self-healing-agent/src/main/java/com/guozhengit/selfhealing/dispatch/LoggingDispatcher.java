package com.guozhengit.selfhealing.dispatch;

import com.guozhengit.selfhealing.alert.AlertPayloads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * D9 占位实现：只记录转派动作。D10 的 AgentTeamDispatcher 用
 * selfhealing.agent.enabled=true 装配，本类随即退位（显式属性开关，
 * 不用 @ConditionalOnMissingBean —— 其在普通 @Component 上的评估顺序不可靠，
 * 曾导致主路径启动失败，见 README 踩坑记录）。
 */
@Component
@ConditionalOnProperty(name = "selfhealing.agent.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingDispatcher implements IncidentDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LoggingDispatcher.class);

    @Override
    public void dispatch(AlertPayloads.AlertItem alert, String rawBody) {
        log.info("[DISPATCH-PLACEHOLDER] alert={} fingerprint={} status={} —— D10 接 AgentScope Team",
                alert.alertName(), alert.fingerprint(), alert.status());
    }
}
