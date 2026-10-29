package com.guozhengit.selfhealing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 原型 1：自愈型运维 Agent —— 入口层。
 *
 * <p>职责边界（DESIGN.md）：只做「收告警、验真、去重、落证据、转派」，
 * 诊断编排在 D10 由 AgentScope harness Team 接管。
 */
@SpringBootApplication
public class SelfHealingAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(SelfHealingAgentApplication.class, args);
    }
}
