# 002: Embabel Autonomous Content Agents

## Summary
Integrate [Embabel](https://github.com/embabel/embabel-agent) (Rod Johnson's AI agent framework for Spring) to create goal-driven autonomous agents that handle content management workflows. Agents could automatically draft blog posts from notes, suggest edits, manage publishing schedules, and orchestrate multi-step content pipelines.

## Why
Embabel is purpose-built for Spring Boot and provides a declarative way to define AI agents with goals, conditions, and actions. This is a natural fit for the existing Spring Boot backend and would showcase cutting-edge Spring ecosystem tooling. It goes beyond simple chat - these are agents that can reason, plan, and execute multi-step workflows.

## Potential Agents
1. **Blog Draft Agent** - Given a topic/outline, researches (via web search), drafts a full blog post in markdown, suggests tags, and saves as a draft
2. **Content Review Agent** - Analyses draft blog posts for readability, SEO, technical accuracy, and suggests improvements
3. **Publishing Scheduler Agent** - Monitors draft posts and suggests optimal publishing times based on content type and past engagement
4. **Skills Updater Agent** - Periodically reviews blog content and job descriptions to suggest new skills or proficiency updates
5. **Content Gap Agent** - Analyses existing content vs skills/experience and suggests blog topics to fill gaps

## Technical Approach
- Add `embabel-agent` dependency to the backend
- Define agent goals using Embabel's `@Goal` and `@Action` annotations
- Wire agents into the admin console with trigger buttons and progress tracking
- Use Spring AI's ChatClient as the underlying LLM provider within Embabel agents
- Agents interact with existing MongoDB repositories for reading/writing content

## Complexity
High. Embabel is a newer framework, so documentation may be evolving. Multi-agent orchestration adds complexity but the Spring-native integration should be smooth.

## Dependencies
- `embabel-agent` library
- Existing Spring AI setup
- Admin console UI for agent management
