export type ChatOutcome = 'ANSWERED' | 'DECLINED' | 'WITHHELD' | 'UNAVAILABLE'

export interface ChatResponse {
  answer: string
  outcome: ChatOutcome
}

export interface SchoolConfig {
  yearGroups: string[]
  authenticated: boolean
  enabled: boolean
}

export interface Turn {
  id: string
  question: string
  response: ChatResponse | null
  failed: boolean
}
