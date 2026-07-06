# Research: Fix AI Blog Titles and Images

## Title Generation for Existing Blogs
- **Decision**: Create a new Mongock migration `V006FixAiBlogTitles` that iterates through all `Blog` entities with titles like "This week in AI", "AI & Tech Roundup", or tagged as "Weekly Digest" but with generic titles.
- **Rationale**: Mongock is the standard way to run data migrations in this project. By injecting `DigestMetadataGenerator` and `BlogImageGenerationService` into the `ChangeUnit`, we can leverage the existing Spring AI and image generation pipeline to fix historical data safely during deployment.
- **Alternatives considered**: A manual script. Rejected because it wouldn't guarantee consistency across environments and would bypass the standard migration pipeline.

## Metadata Generation Input
- **Decision**: Pass the existing `Blog.content` as the `activitySummary` to `DigestMetadataGenerator.generate()`. If we cannot reconstruct the `recentBlogs` and `recentArticles` lists, we will pass empty lists. The generator's prompt simply appends the `activitySummary` so it should work with the raw markdown.
- **Rationale**: The markdown content already contains the summarized articles. GPT-4o-mini is capable of reading the markdown and generating an appropriate title based on the source material.
- **Alternatives considered**: Creating a new AI prompt just for migration. Rejected because reusing `DigestMetadataGenerator` ensures consistency with how future titles are generated.

## Prevention
- **Decision**: Ensure `DigestMetadataGenerator` handles future generations correctly. It was recently updated to avoid "AI & Tech Roundup". No further code changes might be strictly needed for prevention if that update was sufficient, but the migration is essential for existing data.
- **Rationale**: Reusing the updated prompt logic is enough.
