import { BlogListWidget } from './BlogListWidget'
import { EmploymentWidget } from './EmploymentWidget'
import { EventsWidget } from './EventsWidget'
import { NewsWidget } from './NewsWidget'
import { SkillsWidget } from './SkillsWidget'
import type {
  BlogWidgetPayload,
  EmploymentWidgetPayload,
  EventWidgetPayload,
  NewsWidgetPayload,
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
  // Code examples are deliberately not rendered: the tool label ("Fetching code
  // examples") is the whole intended signal. Dumping a title, description and a full code
  // block into the transcript buried the assistant's actual answer underneath it.
  if (widgetKind === 'code') {
    return null
  }
  if (widgetKind === 'blogs') {
    return <BlogListWidget payload={payload as BlogWidgetPayload} />
  }
  if (widgetKind === 'news') {
    return <NewsWidget payload={payload as NewsWidgetPayload} />
  }
  if (widgetKind === 'events') {
    return <EventsWidget payload={payload as EventWidgetPayload} />
  }
  return null
}
