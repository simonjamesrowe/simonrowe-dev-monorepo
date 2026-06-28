import type { SkillWidgetPayload } from '../chatTypes'

interface SkillsWidgetProps {
  payload: SkillWidgetPayload
}

export function SkillsWidget({ payload }: SkillsWidgetProps) {
  if (!payload.groups?.length) return null

  return (
    <div className="chat-widget chat-widget--skills">
      {payload.groups.map(group => (
        <section className="chat-widget__section" key={group.name}>
          <h4 className="chat-widget__title">{group.name}</h4>
          <div className="chat-widget__chips">
            {group.skills.map(skill => (
              <span className="chat-widget__chip" key={skill.name}>
                <span>{skill.name}</span>
                {skill.rating != null && <strong>{skill.rating}/10</strong>}
              </span>
            ))}
          </div>
        </section>
      ))}
    </div>
  )
}
