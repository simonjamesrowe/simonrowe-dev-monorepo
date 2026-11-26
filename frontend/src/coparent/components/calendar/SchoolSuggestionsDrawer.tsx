import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Drawer } from 'vaul';

import type { SchoolReviewAction, SchoolSuggestion } from '../../types/schoolSuggestions';
import { CloseButton } from '../ui/CloseButton';

import { describeWhen } from './schoolSuggestionText';

export interface SchoolSuggestionsDrawerProps {
  open: boolean;
  onClose: () => void;
  suggestions: SchoolSuggestion[];
  /** First names by child id. */
  childNames: Record<string, string>;
  /** Whether any child is linked to the school calendar. */
  anyChildLinked: boolean;
  onAccept: (id: string) => Promise<unknown>;
  onDismiss: (id: string) => Promise<unknown>;
  onReview: (id: string, action: SchoolReviewAction) => Promise<unknown>;
}

/**
 * School events Term Time found for the family calendar. Either parent's decision is enough: a
 * school event is a fact about the children's day, not a change to who has them.
 */
export function SchoolSuggestionsDrawer({
  open,
  onClose,
  suggestions,
  childNames,
  anyChildLinked,
  onAccept,
  onDismiss,
  onReview,
}: SchoolSuggestionsDrawerProps) {
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const act = async (id: string, run: () => Promise<unknown>) => {
    setBusy(id);
    setError(null);
    try {
      await run();
    } catch {
      setError('That did not work. Someone may have answered it already; try again.');
    } finally {
      setBusy(null);
    }
  };

  return (
    <Drawer.Root open={open} onOpenChange={(next) => !next && onClose()} direction="right">
      <Drawer.Portal>
        {/* Utilities are scoped to .coparent-app and Vaul portals to <body>, outside it. */}
        <div className="coparent-app">
          <Drawer.Overlay className="fixed inset-0 z-40 bg-black/40" />
          <Drawer.Content className="expense-drawer">
            <Drawer.Title className="sr-only">From school</Drawer.Title>
            <Drawer.Description className="sr-only">
              School events to add to the family calendar
            </Drawer.Description>
            <div className="expense-drawer__head">
              <h2 className="expense-drawer__title">From school</h2>
              <CloseButton onClick={onClose} />
            </div>
            <div className="expense-drawer__body" data-vaul-no-drag>
              <p className="school-suggest__intro">
                Term Time read these from the school. Either of you can add one to the calendar or
                dismiss it; a dismissed event is not suggested again.
              </p>
              {error && (
                <p role="alert" className="school-suggest__error">
                  {error}
                </p>
              )}
              {suggestions.length === 0 && (
                <p className="school-suggest__empty">
                  {anyChildLinked ? (
                    'Nothing from school needs a look.'
                  ) : (
                    <>
                      No child is linked to the school calendar yet.{' '}
                      <Link to="/family-setup">Link one in Family setup</Link>.
                    </>
                  )}
                </p>
              )}
              <ul className="school-suggest__list">
                {suggestions.map((suggestion) => (
                  <SuggestionItem
                    key={suggestion.id}
                    suggestion={suggestion}
                    childNames={childNames}
                    busy={busy === suggestion.id}
                    onAccept={() => act(suggestion.id, () => onAccept(suggestion.id))}
                    onDismiss={() => act(suggestion.id, () => onDismiss(suggestion.id))}
                    onReview={(action) => act(suggestion.id, () => onReview(suggestion.id, action))}
                  />
                ))}
              </ul>
            </div>
          </Drawer.Content>
        </div>
      </Drawer.Portal>
    </Drawer.Root>
  );
}

function SuggestionItem({
  suggestion,
  childNames,
  busy,
  onAccept,
  onDismiss,
  onReview,
}: {
  suggestion: SchoolSuggestion;
  childNames: Record<string, string>;
  busy: boolean;
  onAccept: () => void;
  onDismiss: () => void;
  onReview: (action: SchoolReviewAction) => void;
}) {
  const { details, review, proposed } = suggestion;
  const names = suggestion.childIds.map((id) => childNames[id]).filter(Boolean);
  const titleId = `school-suggestion-${suggestion.id}`;
  return (
    <li className="school-suggest__item" aria-labelledby={titleId}>
      {review === 'changed' && (
        <p className="school-suggest__flag">The school changed this since you added it</p>
      )}
      {review === 'removed' && (
        <p className="school-suggest__flag">No longer on the school calendar</p>
      )}
      <h3 id={titleId} className="school-suggest__title">
        {details.title}
      </h3>
      {review === 'changed' && proposed ? (
        <p className="school-suggest__when">
          <s>{describeWhen(details)}</s> <span>now {describeWhen(proposed)}</span>
        </p>
      ) : (
        <p className="school-suggest__when">{describeWhen(details)}</p>
      )}
      {(proposed?.location ?? details.location) && (
        <p className="school-suggest__meta">{proposed?.location ?? details.location}</p>
      )}
      {names.length > 0 && <p className="school-suggest__meta">For {names.join(' and ')}</p>}
      {suggestion.sourceTitles.length > 0 && (
        <p className="school-suggest__source">
          From the school: {suggestion.sourceTitles.join(' · ')}
        </p>
      )}
      <div className="school-suggest__actions">
        {review === null && (
          <>
            <button
              type="button"
              className="cp-button cp-button--primary"
              disabled={busy}
              onClick={onAccept}
            >
              Add to calendar
            </button>
            <button
              type="button"
              className="cp-button cp-button--secondary"
              disabled={busy}
              onClick={onDismiss}
            >
              Dismiss
            </button>
          </>
        )}
        {review === 'changed' && (
          <>
            <button
              type="button"
              className="cp-button cp-button--primary"
              disabled={busy}
              onClick={() => onReview('APPLY')}
            >
              Update calendar
            </button>
            <button
              type="button"
              className="cp-button cp-button--secondary"
              disabled={busy}
              onClick={() => onReview('KEEP')}
            >
              Keep as it is
            </button>
          </>
        )}
        {review === 'removed' && (
          <>
            <button
              type="button"
              className="cp-button cp-button--primary"
              disabled={busy}
              onClick={() => onReview('REMOVE')}
            >
              Remove from calendar
            </button>
            <button
              type="button"
              className="cp-button cp-button--secondary"
              disabled={busy}
              onClick={() => onReview('KEEP')}
            >
              Keep it
            </button>
          </>
        )}
      </div>
    </li>
  );
}
