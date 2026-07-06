# Quickstart

This feature introduces a database migration to fix historical AI blog titles and regenerate their featured images.

To run the migration locally:

1. Ensure MongoDB and Elasticsearch are running (via Docker Compose).
2. Ensure `OPENAI_API_KEY` and `OPENAI_BASE_URL` (if applicable) are set so the LLM can generate titles.
3. Start the backend Spring Boot application:
   ```bash
   ./gradlew bootRun
   ```
4. During application startup, Mongock will automatically execute the `V006FixAiBlogTitles` ChangeUnit.
5. Check the logs to verify that historical blogs were identified and updated.
