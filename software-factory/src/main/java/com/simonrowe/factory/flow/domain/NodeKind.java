package com.simonrowe.factory.flow.domain;

/** Whether a node is something the factory runs or something the factory exchanges. */
public enum NodeKind {
  /** A factory module with a Temporal task queue. */
  MODULE,
  /** A thing modules pass between them: Linear, a pull request, main, production, agent-setup. */
  ARTIFACT
}
