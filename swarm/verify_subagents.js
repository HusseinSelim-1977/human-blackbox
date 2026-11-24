// Launch 4 subagents for strategic angle amplification
tools.skill({
  agent: "explore",
  description: "Temporal Sync Deep Dive",
  prompt: "Deep dive into the temporal sync angle for Human BlackBox. Focus on how the rolling buffer's window alignment affects fusion timing. Look for opportunities to optimize the buffer's temporal window, ambient baseline tracking, and chunk granularity. Spawn 6 specialists to cover: window alignment, timestamp precision, ambient drift, chunk boundaries, score decay, and cross-modality sync.",
  sessionID: "ses_001"
});

tools.skill({
  agent: "explore",
  description: "Heuristic Balance Deep Dive",
  prompt: "Deep dive into the heuristic balance angle for Human BlackBox. Focus on how fusion weights interact with detector outputs. Look for opportunities to normalize weights, handle weight drift, and balance the deterministic numpy fallback against learned scores. Spawn 6 specialists to cover: weight normalization, score scaling, ambient adjustment, fallback behavior, weight decay, and cross-modality weighting.",
  sessionID: "ses_002"
});

tools.skill({
  agent: "explore",
  description: "Evidence Roles Deep Dive",
  prompt: "Deep dive into the evidence roles angle for Human BlackBox. Focus on how per-session encrypted evidence digests provide post-hoc audit context. Look for opportunities to optimize the evidence store's encryption strategy, digest granularity, and temporal relevance. Spawn 6 specialists to cover: encryption overhead, digest granularity, temporal relevance, evidence freshness, cross-reference patterns, and storage efficiency.",
  sessionID: "ses_003"
});

tools.skill({
  agent: "explore",
  description: "Alert UX Deep Dive",
  prompt: "Deep dive into the alert UX angle for Human BlackBox. Focus on how the alert sender's payload construction and consent management work. Look for opportunities to enhance user context, reduce alert fatigue, and make evidence digestible. Spawn 6 specialists to cover: payload density, evidence summarization, timing context, user actionability, alert grouping, and consent state.",
  sessionID: "ses_004"
});

console.log("\n4 subagents launched for strategic angle amplification!");