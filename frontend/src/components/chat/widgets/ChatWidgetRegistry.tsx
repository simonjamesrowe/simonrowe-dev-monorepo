import { BlogListWidget } from './BlogListWidget'
import { CodeExampleWidget } from './CodeExampleWidget'
import { EmploymentWidget } from './EmploymentWidget'
import { SkillsWidget } from './SkillsWidget'
import type {
  BlogWidgetPayload,
  CodeWidgetPayload,
  EmploymentWidgetPayload,
  SkillWidgetPayload,
} from '../chatTypes'

interface ChatWidgetProps {
  widgetKind: string
  payload: unknown
}

export function ChatWidget({ widgetKind, payload }: ChatWidgetProps) {
  if (widgetKind === 'skills') {
    return <SkillsWidget payload={payload as SkillWidgetPayload} />
  }
  if (widgetKind === 'employment') {
    return <EmploymentWidget payload={payload as EmploymentWidgetPayload} />
  }
  if (widgetKind === 'code') {
    return <CodeExampleWidget payload={payload as CodeWidgetPayload} />
  }
  if (widgetKind === 'blogs') {
    return <BlogListWidget payload={payload as BlogWidgetPayload} />
  }
  return null
}
