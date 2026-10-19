package com.simonrowe.factory.ticketagent.agent;

/**
 * The agent's structured answer.
 *
 * @param changed whether it edited anything
 * @param summary what it changed and why, or why it changed nothing
 * @param prTitle a conventional-commit title
 * @param prBody the pull request description
 */
public record AgentEdit(boolean changed, String summary, String prTitle, String prBody) {
}
