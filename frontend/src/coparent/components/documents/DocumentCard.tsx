import { Clock, FileText, IdCard, Mail } from 'lucide-react';

import type { FamilyDocument } from '../../types/documents';

import { ChildDots } from './ChildDot';
import {
  EXPIRY_WARN_MONTHS,
  currentVersion,
  dueWithin,
  formatDay,
  formatInstant,
  forText,
  fromSchool,
  isPdf,
  type DocChild,
} from './documentText';

/** One document in the library grid. The whole card opens the drawer. */
export function DocumentCard({
  document,
  children,
  today,
  onOpen,
}: {
  document: FamilyDocument;
  children: DocChild[];
  today: string;
  onOpen: () => void;
}) {
  const latest = currentVersion(document);
  const files = latest?.files ?? [];
  const pdf = files.length === 1 && isPdf(files[0].mimeType);
  const identity = document.section === 'IDENTITY';
  const tags: { key: string; tone: string; text: string; icon?: 'clock' | 'mail' }[] = [];
  if (document.dueOn && document.dueKind === 'EXPIRES') {
    tags.push(
      dueWithin(document.dueOn, today, EXPIRY_WARN_MONTHS)
        ? { key: 'due', tone: 'amber', text: `${document.dueOn < today ? 'Expired' : 'Expires'} ${formatDay(document.dueOn)}`, icon: 'clock' }
        : { key: 'due', tone: 'slate', text: `Valid to ${formatDay(document.dueOn)}` },
    );
  }
  if (document.dueOn && document.dueKind === 'REVIEW') {
    tags.push({ key: 'review', tone: 'amber', text: `Review ${formatDay(document.dueOn)}`, icon: 'clock' });
  }
  if (fromSchool(document)) tags.push({ key: 'school', tone: 'teal', text: 'From school', icon: 'mail' });
  if (document.archivedAt) tags.push({ key: 'archived', tone: 'slate', text: `Archived ${formatInstant(document.archivedAt)}` });

  return (
    <button type="button" className="document-card" onClick={onOpen}>
      <span className="document-card__top">
        <span className="document-thumb" aria-hidden="true">
          {identity ? <IdCard size={18} /> : <FileText size={18} />}
          <small>{pdf ? 'PDF' : `${files.length}p`}</small>
        </span>
        <span className="document-card__text">
          <span className="document-card__title">{document.title}</span>
          <span className="document-card__sub">
            <ChildDots ids={document.childIds} children={children} />
            {forText(document.childIds, children)}
            {document.datedOn ? ` · ${formatDay(document.datedOn)}` : ''}
          </span>
        </span>
      </span>
      {(tags.length > 0 || document.versions.length > 1) && (
        <span className="document-card__foot">
          {tags.map((tag) => (
            <span key={tag.key} className={`document-tag document-tag--${tag.tone}`}>
              {tag.icon === 'clock' && <Clock size={12} aria-hidden="true" />}
              {tag.icon === 'mail' && <Mail size={12} aria-hidden="true" />}
              {tag.text}
            </span>
          ))}
          {document.versions.length > 1 && (
            <span className="document-card__versions">{document.versions.length} versions</span>
          )}
        </span>
      )}
    </button>
  );
}
