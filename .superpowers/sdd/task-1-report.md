# Task 1 Report: Create GuardrailAdvisor

## Implementation Summary
- Created `GuardrailAdvisorTest.java` with a failing test as per the spec, updated to the current Spring AI 1.1.4 API.
- Implemented `GuardrailAdvisor.java` that implements the `CallAdvisor` interface to intercept `ChatClientRequest`.
- Classification prompt calls the LLM with the user's text to determine if the query is `SAFE`, `OFF_TOPIC`, or `HARMFUL`.
- If off-topic or harmful, it short-circuits the call by immediately returning a pivot message instead of proceeding with `chain.nextCall(request)`.
- If safe, it forwards the request to the rest of the chain.
- Refactored implementation details to use the correct `CallAdvisor`, `CallAdvisorChain`, `ChatClientRequest`, and `ChatClientResponse` APIs instead of the `CallAroundAdvisor` and `AdvisedRequest` APIs mentioned in the plan brief (which have been replaced in the 1.1.4 codebase).

## Testing and TDD Evidence
- **RED**: Initially ran the failing test, which produced a compilation error (`cannot find symbol` for `GuardrailAdvisor`) as expected.
- **GREEN**: Implemented the minimal working solution and updated the Checkstyle formatting. Ran `./gradlew test --tests "*GuardrailAdvisorTest*"` which passed successfully. Then ran the entire backend test suite (`./gradlew test`) which also passed successfully.

## Files Changed
- `backend/src/main/java/com/simonrowe/chat/GuardrailAdvisor.java`
- `backend/src/test/java/com/simonrowe/chat/GuardrailAdvisorTest.java`

## Self-Review Findings
- **Completeness:** All aspects of the task were addressed. The prompt classification strictly follows the LLM single-word output condition.
- **Quality:** Maintained the strict 2-space indentation style used within the codebase and ensured Checkstyle passed successfully without warnings or errors. 
- **Discipline:** No extraneous code was built beyond what was asked. The use of `CallAdvisor` complies precisely with the existing codebase's Spring AI 1.1.4 conventions.
- **Testing:** Provided test coverage mocks out the underlying LLM call to verify correctly pivoted/short-circuited responses and accurately captures the advisor pattern behavior.

## Issues or Concerns
- The task brief references deprecated or outdated advisor interfaces (`CallAroundAdvisor`, `AdvisedRequest`, `AdvisedResponse`) for Spring AI 1.1.4. I went ahead and corrected them to use the 1.1.4 compliant `CallAdvisor` interface to match the existing patterns used in `ContextAwareQuestionAnswerAdvisor.java` and `MessageChatMemoryAdvisor`. This was handled automatically without changing the expected behavior of the classification block.

## Review Fixes
- **Important**: Updated the `pivotMessage` to explicitly direct the user to Simon's profile ("Please check out Simon's profile to learn more about his skills and experience.").
- **Minor**: Wrapped the classification logic in a `try-catch` block to fail-open (`return chain.nextCall(request)`) on exceptions or API timeouts. Added defensive null checks to avoid `NullPointerException` on `classificationResponse.getResult().getOutput().getText()`. Added unit tests `testExceptionFailOpen` and `testNullGenerationFailOpen` to verify this behavior.
