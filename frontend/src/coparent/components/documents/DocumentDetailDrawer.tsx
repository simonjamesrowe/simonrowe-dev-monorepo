import {
  Archive,
  ArchiveRestore,
  CircleAlert,
  Download,
  FileText,
  IdCard,
  Info,
  Maximize2,
  Pencil,
  RefreshCw,
  Upload,
} from 'lucide-react';
import { useState } from 'react';

import { useArchiveDocument } from '../../hooks/api/useDocuments';
import type {
  DocumentDetail,
  DocumentHistoryAction,
  DocumentVersion,
  FamilyDocument,
} from '../../types/documents';
import { useToast } from '../ui/ToastProvider';

import { ChildDots } from './ChildDot';
import { documentErrorMessage } from './documentErrors';
import { DocumentSheet } from './DocumentSheet';
import {
  BRIEFING_EXPIRY_MONTHS,
  EXPIRY_WARN_MONTHS,
  REVIEW_WARN_MONTHS,
  currentVersion,
  dueWithin,
  formatDay,
  formatInstant,
  forText,
  isPdf,
  sectionLabel,
  untilText,
  type DocChild,
} from './documentText';
import { useDocumentFileUrl } from './useDocumentFileUrl';

const HISTORY: Record<DocumentHistoryAction, string> = {
  CREATED: 'Added',
  EDITED: 'Details edited',
  NEW_VERSION: 'New version uploaded',
  ARCHIVED: 'Archived',
  RESTORED: 'Restored',
};

const SOURCE: Record<string, string> = {
  SCHOOL_LETTER: 'A letter from school',
  SCHOOL_REPORT: 'A school report from Insight Tracking',
  QUICK_ADD: 'Quick add',
};

function versionLine(version: DocumentVersion): string {
  const parts = [version.datedOn ? `dated ${formatDay(version.datedOn)}` : null];
  if (version.dueOn) {
    parts.push(`${version.dueKind === 'REVIEW' ? 'review' : 'expires'} ${formatDay(version.dueOn)}`);
  }
  const text = parts.filter(Boolean).join(', ');
  return text ? text.charAt(0).toUpperCase() + text.slice(1) : `${version.files.length} file${version.files.length === 1 ? '' : 's'}`;
}

function Preview({ familyId, document }: { familyId: string; document: FamilyDocument }) {
  const files = currentVersion(document)?.files ?? [];
  const first = files[0];
  const image = first && !isPdf(first.mimeType);
  const { url, failed } = useDocumentFileUrl(familyId, image ? document.id : undefined, image ? first.fileId : undefined);
  return (
    <div className="document-preview">
      {image && url ? (
        <img src={url} alt={`${document.title}, first page`} className="document-preview__img" />
      ) : (
        <span className="document-preview__paper" aria-hidden="true">
          {document.section === 'IDENTITY' ? <IdCard size={34} /> : <FileText size={34} />}
          <small>{first && isPdf(first.mimeType) ? 'PDF' : failed ? 'Could not load' : image ? 'Loading…' : ''}</small>
        </span>
      )}
      {files.length > 0 && (
        <span className="document-preview__pages">
          {files.length === 1 && first && isPdf(first.mimeType) ? 'PDF' : `Page 1 of ${files.length}`}
        </span>
      )}
    </div>
  );
}

export interface DocumentDetailDrawerProps {
  open: boolean;
  familyId: string;
  /** The listed copy, shown at once; replaced by the detail (with history) once it loads. */
  document: FamilyDocument | DocumentDetail;
  children: DocChild[];
  /** "you" or a first name for a parent id. */
  nameOf: (parentId: string | null | undefined) => string;
  today: string;
  /** The other parent's first name when they are active, for the toasts. */
  otherActiveName: string | null;
  onClose: () => void;
  onView: () => void;
  onDownload: (version: DocumentVersion) => void;
  onNewVersion: () => void;
  onEdit: () => void;
}

/** One document: preview, dates, versions, history and what either parent can do with it. */
export function DocumentDetailDrawer({
  open,
  familyId,
  document,
  children,
  nameOf,
  today,
  otherActiveName,
  onClose,
  onView,
  onDownload,
  onNewVersion,
  onEdit,
}: DocumentDetailDrawerProps) {
  const archive = useArchiveDocument();
  const { showToast } = useToast();
  const [error, setError] = useState<string | null>(null);
  const history = 'history' in document ? document.history : null;
  const latest = currentVersion(document);
  const earlier = document.versions.slice(0, -1).reverse();
  const expires = document.dueOn && document.dueKind === 'EXPIRES' ? document.dueOn : null;
  const review = document.dueOn && document.dueKind === 'REVIEW' ? document.dueOn : null;

  const toggleArchive = async () => {
    setError(null);
    const restore = !!document.archivedAt;
    try {
      await archive.mutateAsync({ familyId, id: document.id, version: document.version, restore });
      showToast({
        variant: 'success',
        title: restore ? 'Restored to the library' : 'Archived',
        description: restore
          ? `${document.title} is back in the library.`
          : `${otherActiveName ? `${otherActiveName} will get an email, and either` : 'Either'} of you can restore it.`,
      });
      onClose();
    } catch (failure) {
      setError(documentErrorMessage(failure, 'That did not work. Try again.'));
    }
  };

  const expiryCallout = () => {
    if (!expires || !dueWithin(expires, today, EXPIRY_WARN_MONTHS)) return null;
    if (expires < today) return `Expired ${untilText(expires, today)}. Upload the renewed one here when it arrives.`;
    const travel = document.section === 'IDENTITY'
      ? ' Many countries want 6 months left on arrival, so renew before booking travel.'
      : '';
    const again = dueWithin(expires, today, BRIEFING_EXPIRY_MONTHS)
      ? ''
      : ` We'll remind you both again ${BRIEFING_EXPIRY_MONTHS} months before.`;
    return `Expires ${untilText(expires, today)}.${travel}${again}`;
  };
  const callout = expiryCallout()
    ?? (review && dueWithin(review, today, REVIEW_WARN_MONTHS)
      ? `Due for review ${review < today ? 'since' : 'by'} ${formatDay(review)}.`
      : null);

  return (
    <DocumentSheet
      open={open}
      title={`${forText(document.childIds, children)}: ${document.title}`}
      description="A document in the family library"
      leading={<ChildDots ids={document.childIds} children={children} />}
      subtitle={`${sectionLabel(document.section)} · added by ${nameOf(document.createdBy)} on ${formatInstant(document.createdAt)}`}
      onClose={onClose}
      footer={(
        <>
          <button type="button" className="cp-button cp-button--primary cp-button--sm" onClick={onView} disabled={!latest?.files.length}>
            <Maximize2 size={15} aria-hidden="true" /> View full screen
          </button>
          <button type="button" className="cp-button cp-button--secondary cp-button--sm" onClick={() => latest && onDownload(latest)} disabled={!latest?.files.length}>
            <Download size={15} aria-hidden="true" /> Download
          </button>
          <button type="button" className="cp-button cp-button--secondary cp-button--sm" onClick={onNewVersion}>
            <Upload size={15} aria-hidden="true" /> Upload {expires ? 'renewed' : 'newer'} version
          </button>
          <button type="button" className="cp-button cp-button--secondary cp-button--sm" onClick={onEdit}>
            <Pencil size={15} aria-hidden="true" /> Edit details
          </button>
          {document.archivedAt ? (
            <button type="button" className="cp-button cp-button--secondary cp-button--sm" onClick={toggleArchive} disabled={archive.isPending}>
              <ArchiveRestore size={15} aria-hidden="true" /> Restore
            </button>
          ) : (
            <button type="button" className="cp-button cp-button--quiet cp-button--sm" onClick={toggleArchive} disabled={archive.isPending}>
              <Archive size={15} aria-hidden="true" /> Archive
            </button>
          )}
        </>
      )}
    >
      <Preview familyId={familyId} document={document} />
      {error && (
        <p className="document-callout document-callout--rose" role="alert">
          <CircleAlert size={16} aria-hidden="true" />
          <span>{error}</span>
        </p>
      )}
      {callout && (
        <p className="document-callout document-callout--amber">
          <CircleAlert size={16} aria-hidden="true" />
          <span>{callout}</span>
        </p>
      )}
      {document.archivedAt && (
        <p className="document-callout document-callout--slate">
          <Info size={16} aria-hidden="true" />
          <span>
            Archived by {nameOf(document.archivedBy)} on {formatInstant(document.archivedAt)}. It's hidden from
            the library, not deleted.
          </span>
        </p>
      )}
      <dl className="document-facts">
        <dt>For</dt>
        <dd>{forText(document.childIds, children)}</dd>
        <dt>Section</dt>
        <dd>{sectionLabel(document.section)}</dd>
        {document.datedOn && (
          <>
            <dt>Dated</dt>
            <dd>{formatDay(document.datedOn)}</dd>
          </>
        )}
        {expires && (
          <>
            <dt>Expires</dt>
            <dd>{formatDay(expires)}</dd>
          </>
        )}
        {review && (
          <>
            <dt>Review due</dt>
            <dd>{formatDay(review)}</dd>
          </>
        )}
        {SOURCE[document.source.kind] && (
          <>
            <dt>Came from</dt>
            <dd>{SOURCE[document.source.kind]}</dd>
          </>
        )}
        {document.note && (
          <>
            <dt>Note</dt>
            <dd className="document-facts__note">{document.note}</dd>
          </>
        )}
      </dl>

      {earlier.length > 0 && latest && (
        <section>
          <h3 className="document-h3">Versions</h3>
          <ul className="document-log">
            <li>
              <RefreshCw size={15} aria-hidden="true" />
              <span>Current: {versionLine(latest)}</span>
              <time dateTime={latest.uploadedAt}>{formatInstant(latest.uploadedAt)}</time>
            </li>
            {earlier.map((version) => (
              <li key={version.versionId} className="document-log__muted">
                <FileText size={15} aria-hidden="true" />
                <span>Previous: {versionLine(version)}</span>
                <button
                  type="button"
                  className="cp-button cp-button--quiet cp-button--sm"
                  onClick={() => onDownload(version)}
                  aria-label={`Download previous version uploaded ${formatInstant(version.uploadedAt)}`}
                >
                  Download
                </button>
                <time dateTime={version.uploadedAt}>{formatInstant(version.uploadedAt)}</time>
              </li>
            ))}
          </ul>
        </section>
      )}

      <section>
        <h3 className="document-h3">History</h3>
        {history ? (
          <ul className="document-log">
            {history.map((item, index) => (
              <li key={`${item.action}-${item.at}-${index}`}>
                <span>{HISTORY[item.action] ?? item.action} by {nameOf(item.parentId)}</span>
                <time dateTime={item.at}>{formatInstant(item.at)}</time>
              </li>
            ))}
          </ul>
        ) : (
          <p className="document-muted">Loading history…</p>
        )}
      </section>
    </DocumentSheet>
  );
}
