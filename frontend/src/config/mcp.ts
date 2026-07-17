// Tools that must not be runnable from the public page. They still show full
// documentation, but a "not runnable here" badge replaces the Run form to prevent
// public abuse (e.g. email spam via submitContactForm). Adding a future destructive
// tool means adding its name here — all other tools remain fully automatic.
export const DENYLISTED_TOOLS: readonly string[] = ['submitContactForm']

// The already-public production MCP endpoint external clients connect to. The prod
// nginx-proxy routes api.simonrowe.dev -> backend:8080, so this works independently
// of the frontend's same-origin /mcp proxy.
export const PUBLIC_MCP_URL = 'https://api.simonrowe.dev/mcp'

export function isDenylisted(toolName: string): boolean {
  return DENYLISTED_TOOLS.includes(toolName)
}
