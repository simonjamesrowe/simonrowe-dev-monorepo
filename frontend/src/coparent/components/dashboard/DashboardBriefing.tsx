import { Fragment, useMemo } from 'react';

import type { Event } from '../../types/calendar';
import { dateToYmd } from '../calendar/recurrence';

import {
  type BriefingChild,
  type BriefingItem,
  addDaysTo,
  headlinePredicate,
  isFinished,
  itemsByDay,
  longDate,
  shortWeekday,
} from './briefing';

export interface BriefingAction {
  label: string;
  onClick: () => void;
  primary?: boolean;
  disabled?: boolean;
}

/** Something waiting on the signed-in parent, in the order the page lists them. */
export interface NeedsYouItem {
  id: string;
  kind: 'invite' | 'expense' | 'permission' | 'schedule' | 'message';
  title: string;
  detail: string;
  actions: BriefingAction[];
}

export interface BriefingMoney {
  headline: string;
  detail: string;
  actions: BriefingAction[];
}

export interface DashboardBriefingProps {
  /** Passed in so tests can fix the time; the page passes the live clock. */
  now: Date;
  children: BriefingChild[];
  events: Event[];
  /** "you", the other parent's first name, or null when no custody event covers today. */
  custodyParentName: string | null;
  handover: { date: string; parentName: string } | null;
  needs: NeedsYouItem[];
  money: BriefingMoney | null;
  onQuickAdd?: () => void;
  onAddEvent: () => void;
  onOpenCalendar: () => void;
  onOpenEvent: (eventId: string, occurrence?: string) => void;
  onEditProfile: () => void;
  onFamilySetup: () => void;
}

const DEFAULT_START_HOUR = 7;
const DEFAULT_END_HOUR = 21;
const COMING_UP_DAYS = 5;

const minutesOf = (time: string) => {
  const [hours = 0, minutes = 0] = time.split(':').map(Number);
  return hours * 60 + minutes;
};

const timeRange = (item: BriefingItem) => {
  if (item.allDay || !item.startTime) return 'All day';
  return item.endTime ? `${item.startTime}–${item.endTime}` : item.startTime;
};

/**
 * "Ethan's football game" for a sentence. The first letter is lowered only for an ordinary word,
 * so "SLDA class" keeps its capitals.
 */
const describeItem = (item: BriefingItem) => {
  const title = /^[A-Z][a-z]/.test(item.title)
    ? item.title.charAt(0).toLowerCase() + item.title.slice(1)
    : item.title;
  return item.children.length === 1 ? `${item.children[0].firstName}'s ${title}` : title;
};

function Kids({ children }: { children: BriefingChild[] }) {
  return (
    <>
      {children.map((child) => (
        <span key={child.id} className={`cp-brief__kid cp-brief__kid--tone-${child.tone}`}>
          {child.firstName}
        </span>
      ))}
    </>
  );
}

function ActionButtons({ actions }: { actions: BriefingAction[] }) {
  return (
    <div className="cp-brief__actions">
      {actions.map((action) => (
        <button
          key={action.label}
          type="button"
          className={`cp-button cp-button--sm ${action.primary ? 'cp-button--primary' : 'cp-button--secondary'}`}
          onClick={action.onClick}
          disabled={action.disabled}
        >
          {action.label}
        </button>
      ))}
    </div>
  );
}

/** The day as a line from morning to evening, with today's events laid on it. */
function TodayStrip({ items, now }: { items: BriefingItem[]; now: Date }) {
  const timed = items.filter((item) => item.startTime && !item.allDay);
  const startHour = Math.min(
    DEFAULT_START_HOUR,
    ...timed.map((item) => Math.floor(minutesOf(item.startTime ?? '') / 60)),
  );
  const endHour = Math.max(
    DEFAULT_END_HOUR,
    ...timed.map((item) => Math.min(24, Math.ceil(minutesOf(item.endTime ?? item.startTime ?? '') / 60))),
  );
  const span = (endHour - startHour) * 60;
  const percentOf = (minutes: number) =>
    Math.min(100, Math.max(0, ((minutes - startHour * 60) / span) * 100));
  const nowMinutes = now.getHours() * 60 + now.getMinutes();
  const showNow = nowMinutes >= startHour * 60 && nowMinutes <= endHour * 60;
  const labels = Array.from(
    { length: Math.floor((endHour - startHour) / 2) + 1 },
    (_, index) => startHour + index * 2,
  );

  // The list below says the same thing in words, so the strip is decoration to a screen reader.
  return (
    <div className="cp-brief__strip" aria-hidden="true">
      <div className="cp-brief__strip-track">
        {timed.map((item) => {
          const start = minutesOf(item.startTime ?? '');
          const end = item.endTime ? minutesOf(item.endTime) : start + 60;
          const tone = item.children.length === 1 ? item.children[0].tone : 'shared';
          return (
            <div
              key={`${item.eventId}-${item.startTime}`}
              className={`cp-brief__strip-block cp-brief__strip-block--tone-${tone}${
                isFinished(item, now) ? ' cp-brief__strip-block--past' : ''
              }`}
              style={{
                left: `${percentOf(start)}%`,
                width: `${Math.max(percentOf(Math.max(end, start + 15)) - percentOf(start), 1)}%`,
              }}
            >
              {item.title}
            </div>
          );
        })}
        {showNow && (
          <div
            className="cp-brief__strip-now"
            style={{ left: `${percentOf(nowMinutes)}%` }}
            data-testid="briefing-now"
          />
        )}
      </div>
      <div className="cp-brief__strip-hours">
        {labels.map((hour) => (
          <span key={hour}>{hour === 12 ? '12pm' : hour > 12 ? `${hour - 12}pm` : `${hour}am`}</span>
        ))}
      </div>
    </div>
  );
}

function DayList({
  items,
  now,
  isToday,
  onOpenEvent,
}: {
  items: BriefingItem[];
  now: Date;
  isToday: boolean;
  onOpenEvent: DashboardBriefingProps['onOpenEvent'];
}) {
  return (
    <ul className="cp-brief__rows">
      {items.map((item) => {
        const finished = isToday && isFinished(item, now);
        return (
          <li key={`${item.eventId}-${item.date}-${item.startTime ?? 'all-day'}`}>
            <button
              type="button"
              className={`cp-brief__row${finished ? ' cp-brief__row--past' : ''}`}
              onClick={() => onOpenEvent(item.eventId, item.occurrence)}
            >
              <span className="cp-brief__time">{timeRange(item)}</span>
              <span className="cp-brief__what">
                <Kids children={item.children} />
                <span className="cp-brief__title">{item.title}</span>
                {item.location && <span className="cp-brief__where">{item.location}</span>}
              </span>
              <span className="cp-brief__status">{finished ? 'Done' : ''}</span>
            </button>
          </li>
        );
      })}
    </ul>
  );
}

export function DashboardBriefing({
  now,
  children,
  events,
  custodyParentName,
  handover,
  needs,
  money,
  onQuickAdd,
  onAddEvent,
  onOpenCalendar,
  onOpenEvent,
  onEditProfile,
  onFamilySetup,
}: DashboardBriefingProps) {
  const today = dateToYmd(now);
  const lastDay = dateToYmd(addDaysTo(today, COMING_UP_DAYS + 1));
  const days = useMemo(
    () => itemsByDay(events, children, today, lastDay),
    [events, children, today, lastDay],
  );
  const dayKeys = Array.from(days.keys());
  const todayItems = days.get(today) ?? [];
  const tomorrow = dayKeys[1];
  const tomorrowItems = (tomorrow && days.get(tomorrow)) || [];
  const comingUp = dayKeys.slice(2);
  const remaining = todayItems.filter((item) => !isFinished(item, now));
  const hasCustodySchedule = events.some((event) => event.type.trim().toLowerCase() === 'custody');
  const nextToday = remaining.find((item) => !item.allDay);
  const nextTomorrow = tomorrowItems.find((item) => !item.allDay);
  const next = nextToday
    ? { item: nextToday, when: 'Next' }
    : nextTomorrow
      ? { item: nextTomorrow, when: 'Tomorrow' }
      : null;
  const nextLine = next ? `${next.when}: ${describeItem(next.item)} at ${next.item.startTime}.` : null;

  return (
    <main className="cp-brief">
      <div className="cp-brief__top">
        <span className="cp-brief__eyebrow">{longDate(now)}</span>
        <div className="cp-brief__actions">
          <button type="button" className="cp-button cp-button--secondary cp-button--sm" onClick={onAddEvent}>
            Add event
          </button>
          {onQuickAdd && (
            <button type="button" className="cp-button cp-button--primary cp-button--sm" onClick={onQuickAdd}>
              Quick add
            </button>
          )}
        </div>
      </div>

      {children.length === 0 ? (
        <>
          <h1 className="cp-brief__headline">Add your children to get started.</h1>
          <p className="cp-brief__sub">
            <button type="button" className="cp-brief__link" onClick={onFamilySetup}>
              Open family setup
            </button>
          </p>
        </>
      ) : (
        <>
          <h1 className="cp-brief__headline">
            {children.map((child, index) => (
              <Fragment key={child.id}>
                {index > 0 && (index === children.length - 1 ? ' and ' : ', ')}
                <span className={`cp-brief__name cp-brief__name--tone-${child.tone}`}>
                  {child.firstName}
                </span>
              </Fragment>
            ))}{' '}
            {headlinePredicate({
              childCount: children.length,
              custodyParentName,
              remainingToday: remaining.length,
            })}
          </h1>
          <p className="cp-brief__sub">
            {handover && (
              <>
                Next handover <strong>{longDate(addDaysTo(handover.date, 0))}</strong>, to{' '}
                {handover.parentName}.{' '}
              </>
            )}
            {nextLine}
            {!hasCustodySchedule && (
              <span className="cp-brief__hint">
                {' '}
                Add custody days to the calendar to see here who has the children.
              </span>
            )}
          </p>
        </>
      )}

      <TodayStrip items={todayItems} now={now} />

      <section className="cp-brief__section" aria-labelledby="cp-brief-needs">
        <h2 id="cp-brief-needs" className="cp-brief__section-title">
          Needs you
        </h2>
        {needs.length === 0 ? (
          <p className="cp-brief__empty">Nothing needs you right now.</p>
        ) : (
          <div className="cp-brief__asks">
            {needs.map((item) => (
              <div key={item.id} className={`cp-brief__ask cp-brief__ask--${item.kind}`}>
                <div>
                  <strong>{item.title}</strong>
                  <p>{item.detail}</p>
                </div>
                <ActionButtons actions={item.actions} />
              </div>
            ))}
          </div>
        )}
      </section>

      <section className="cp-brief__section" aria-labelledby="cp-brief-today">
        <div className="cp-brief__section-head">
          <h2 id="cp-brief-today" className="cp-brief__section-title">
            Today
          </h2>
          <button type="button" className="cp-brief__link" onClick={onOpenCalendar}>
            Open calendar
          </button>
        </div>
        {todayItems.length === 0 ? (
          <p className="cp-brief__empty">Nothing on the calendar today.</p>
        ) : (
          <DayList items={todayItems} now={now} isToday onOpenEvent={onOpenEvent} />
        )}
      </section>

      <section className="cp-brief__section" aria-labelledby="cp-brief-tomorrow">
        <h2 id="cp-brief-tomorrow" className="cp-brief__section-title">
          Tomorrow
        </h2>
        {tomorrowItems.length === 0 ? (
          <p className="cp-brief__empty">Nothing on the calendar tomorrow.</p>
        ) : (
          <DayList items={tomorrowItems} now={now} isToday={false} onOpenEvent={onOpenEvent} />
        )}
      </section>

      <section className="cp-brief__section" aria-labelledby="cp-brief-coming">
        <h2 id="cp-brief-coming" className="cp-brief__section-title">
          Coming up
        </h2>
        <div className="cp-brief__days">
          {comingUp.map((day) => {
            const items = days.get(day) ?? [];
            return (
              <div key={day} className="cp-brief__day">
                <div className="cp-brief__day-name">
                  {shortWeekday(day)}
                  <small>{Number(day.slice(8))}</small>
                </div>
                <div className="cp-brief__day-items">
                  {items.length === 0 && <span className="cp-brief__day-empty">Nothing on</span>}
                  {items.map((item) => (
                    <button
                      key={`${item.eventId}-${item.startTime ?? 'all-day'}`}
                      type="button"
                      className="cp-brief__day-item"
                      onClick={() => onOpenEvent(item.eventId, item.occurrence)}
                    >
                      <span className="cp-brief__time">{item.allDay ? 'All day' : item.startTime}</span>
                      <span>
                        <Kids children={item.children} /> {item.title}
                      </span>
                    </button>
                  ))}
                </div>
              </div>
            );
          })}
        </div>
      </section>

      {money && (
        <section className="cp-brief__money" aria-label="Shared expenses">
          <p className="cp-brief__money-line">
            <strong>{money.headline}</strong>
            {money.detail}
          </p>
          <ActionButtons actions={money.actions} />
        </section>
      )}

      <footer className="cp-brief__footer">
        <button type="button" className="cp-brief__link" onClick={onFamilySetup}>
          Family setup
        </button>
        <button type="button" className="cp-brief__link" onClick={onEditProfile}>
          Your profile
        </button>
      </footer>
    </main>
  );
}
