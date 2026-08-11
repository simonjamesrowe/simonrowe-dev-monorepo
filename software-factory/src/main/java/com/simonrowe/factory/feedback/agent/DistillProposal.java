package com.simonrowe.factory.feedback.agent;

/** The distiller's declaration of what it changed and the PR copy to use. */
public record DistillProposal(boolean changed, String reason, String prTitle, String prBody) {
}
