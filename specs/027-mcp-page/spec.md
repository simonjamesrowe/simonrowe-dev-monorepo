# Feature Specification: Public MCP Tools Page

**Feature Branch**: `027-mcp-page`

**Created**: 2026-07-17

**Status**: Draft

**Input**: User description: "Add a public /mcp page that documents and interactively tests every MCP server tool via the live MCP protocol, plus copy-paste client connection instructions"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Discover the available MCP tools (Priority: P1)

A visitor navigates to the public `/mcp` page and sees a live, self-updating catalogue of every tool the site's MCP server exposes. Each tool is presented as a card showing its name, a human-readable description, and its parameters (including which are required and what values are allowed).

**Why this priority**: This is the core value of the page — letting anyone (developers, recruiters, the curious) understand what the site's AI assistant can do. It must reflect the live server so it never drifts out of date. It stands alone as a useful reference even without the ability to run tools.

**Independent Test**: Load `/mcp` against a server exposing a known set of tools and confirm one card per tool renders with the correct name, description, and parameter list, matching what the server reports.

**Acceptance Scenarios**:

1. **Given** the MCP server is reachable and exposes N tools, **When** the visitor opens `/mcp`, **Then** the page displays exactly N tool cards, each with the tool's name, description, and parameters.
2. **Given** a new tool is added to the backend, **When** the visitor reloads `/mcp` (with no frontend redeployment), **Then** the new tool appears as an additional card automatically.
3. **Given** the MCP server is unreachable or the catalogue cannot be retrieved, **When** the visitor opens `/mcp`, **Then** the page shows a single clear error message rather than a broken or empty layout.

---

### User Story 2 - Run a tool and see its result (Priority: P2)

A visitor fills in a tool's parameters using a form generated from that tool's parameter definition, runs the tool, and sees the returned result rendered on the same card.

**Why this priority**: Turns the page from documentation into a live demo, letting visitors experience the tools directly. It depends on the catalogue (P1) already rendering.

**Independent Test**: With a mocked or live server, fill in a tool's form, trigger execution, and confirm the request is sent with the entered values and the returned content is displayed on that card.

**Acceptance Scenarios**:

1. **Given** a tool with text, choice, and true/false parameters, **When** the card renders, **Then** the form shows a text field for text parameters, a selection control for choice parameters, and a toggle for true/false parameters, with required parameters marked.
2. **Given** a visitor has entered valid parameter values, **When** they run the tool, **Then** the result is fetched and displayed in a readable, formatted panel on that card.
3. **Given** a tool execution fails, **When** the error is returned, **Then** only that card shows an error state and all other cards remain usable.
4. **Given** a tool is on the destructive/abuse denylist, **When** its card renders, **Then** it shows full documentation but no run form, and instead displays a "not runnable here" badge.

---

### User Story 3 - Connect an external MCP client (Priority: P3)

A developer wants to connect their own MCP-capable client (Claude Code, Codex CLI, Gemini CLI) to the site's MCP server. The page provides a "Connect your client" section with the public server URL and a copy-paste configuration snippet for each supported client, each with a one-click copy button.

**Why this priority**: Extends the page's usefulness beyond the in-browser harness, letting developers wire the server into their own tooling. It is standalone informational content independent of the live catalogue.

**Independent Test**: Load `/mcp` and confirm the connect section renders a snippet for each supported client, each containing the public MCP server URL and a working copy button.

**Acceptance Scenarios**:

1. **Given** the visitor opens `/mcp`, **When** they view the "Connect your client" section, **Then** they see a distinct copy-paste snippet for each supported client that includes the public MCP server URL.
2. **Given** the visitor clicks a snippet's copy button, **When** the copy succeeds, **Then** the snippet content is placed on the clipboard.

---

### Edge Cases

- **Server unreachable at load** → page-level error message, no partial/broken UI.
- **Empty catalogue (zero tools)** → page renders the connect instructions and an empty-but-valid tools area (no crash).
- **Tool with no parameters** → card renders with a run button and no form fields.
- **Tool execution returns an error or times out** → per-card error state; other cards unaffected.
- **Result content is large or non-textual** → result panel presents it in a readable, formatted way without breaking layout.
- **Clipboard copy unavailable/denied** → copy button fails gracefully without breaking the page.
- **Server response arrives in a streaming vs. non-streaming format** → both handled transparently so the visitor sees the same result.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST expose a public, unauthenticated page at the `/mcp` route.
- **FR-002**: The page MUST retrieve its tool catalogue live from the site's own MCP server at page load, so no frontend change is required when tools are added, removed, or changed.
- **FR-003**: The page MUST render one card per tool, showing the tool's name, description, and parameters (including whether each parameter is required and any restricted set of allowed values).
- **FR-004**: Each runnable tool's card MUST generate an input form derived from that tool's parameter definition: a text input by default, a selection control for parameters restricted to a set of values, and a toggle for true/false parameters.
- **FR-005**: The page MUST allow a visitor to execute a runnable tool with the entered parameter values and display the returned result in a readable, formatted panel on that tool's card.
- **FR-006**: The system MUST maintain a frontend denylist of destructive/abuse-prone tools (initially the contact-form submission tool); denylisted tools MUST still show full documentation but MUST NOT offer a run form, displaying a "not runnable here" badge instead.
- **FR-007**: A failure to connect to the server or retrieve the catalogue MUST produce a single page-level error state; a failure executing an individual tool MUST be isolated to that tool's card without affecting others.
- **FR-008**: The page MUST include a "Connect your client" section presenting the public MCP server URL and a copy-paste configuration snippet for each supported client (Claude Code, Codex CLI, Gemini CLI), each with a copy-to-clipboard control.
- **FR-009**: The page MUST be reachable via navigation from both the desktop and mobile site navigation.
- **FR-010**: The page MUST display an appropriate loading state while connecting and retrieving the catalogue, consistent with other public pages on the site.
- **FR-011**: The page MUST record a page view and set the document title consistent with the site's other public pages.
- **FR-012**: The system MUST route requests from the `/mcp` path to the MCP server in both local development and production environments, supporting streaming responses.
- **FR-013**: The connect-your-client snippets MUST point external clients at the already-public production MCP endpoint, independent of the in-browser page's request routing.

### Key Entities *(include if feature involves data)*

- **MCP Tool**: A capability exposed by the server. Attributes: name, human-readable description, and a parameter definition (parameter names, types, allowed values, required flag). Retrieved live from the server.
- **Tool Parameter**: A single input to a tool. Attributes: name, type (text / choice / true-false), optional allowed-value set, required flag, description.
- **Tool Result**: The output of executing a tool. Contains formatted content (text or structured data) to display, or an error.
- **Client Connection Snippet**: Static, per-client configuration text parameterised only by the public MCP server URL.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: When the backend exposes a new tool, it appears on the page after a reload with zero frontend code changes and zero frontend redeployment.
- **SC-002**: The page renders exactly one card per tool reported by the server, with 100% of tool names, descriptions, and parameters matching the server's live catalogue.
- **SC-003**: A visitor can execute a non-denylisted tool and view its result without leaving the page.
- **SC-004**: Denylisted (destructive) tools are never runnable from the page — 0% of denylisted tools present a run form.
- **SC-005**: A failed connection results in exactly one clear error message and no broken layout.
- **SC-006**: Each supported client has a copy-paste snippet containing the correct public MCP server URL, verified valid against each client's current configuration format at implementation time.
- **SC-007**: A failure running one tool leaves all other tool cards fully usable.

## Assumptions

- The site's MCP server is already live, unauthenticated, and rate-limited; no backend/server changes are required for this feature.
- The MCP tool definitions the server reports are sufficient to build input forms (parameter names, types, allowed values, required flags).
- The in-browser page reaches the MCP server on the same origin via existing request routing; external clients use the already-public production endpoint.
- The existing per-endpoint rate limit is sufficient protection for the public harness; the only additional abuse control needed is the frontend denylist for destructive tools.
- The initial denylist contains the contact-form submission tool; adding future destructive tools requires a one-line denylist update (accepted trade-off).
- Reasonable defaults from other public pages apply: loading indicator, error message component, page-view tracking, and document title behaviour are reused for consistency.
- The exact client configuration snippet formats must be verified against each client's current documentation at implementation time, as these change frequently.
