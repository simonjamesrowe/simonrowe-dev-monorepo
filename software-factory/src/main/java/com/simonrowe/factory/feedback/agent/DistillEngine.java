package com.simonrowe.factory.feedback.agent;

import com.simonrowe.factory.feedback.domain.Lesson;
import java.util.List;
import java.util.function.Consumer;

/** Integrates harvested lessons into guidance files with a writing-quality model. */
public interface DistillEngine {
  DistillProposal distill(
      DistillTarget target, List<Lesson> lessons, Consumer<String> heartbeat);
}
