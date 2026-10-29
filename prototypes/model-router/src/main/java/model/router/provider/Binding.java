package model.router.provider;

/** 引擎与其调度优先级的绑定，数字越小越先被尝试。 */
public record Binding(TtsEngine engine, int priority) {}
