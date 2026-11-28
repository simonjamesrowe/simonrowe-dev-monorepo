import { ExternalLink, FileText, Info, Mail, Plus } from 'lucide-react';
import { useState } from 'react';

import type { DocumentSection, SchoolOffer } from '../../types/documents';

import { ChildDots } from './ChildDot';
import { DocumentSheet } from './DocumentSheet';
import {
  SECTIONS,
  formatInstant,
  joinNames,
  possessive,
  safeReportLink,
  sectionLabel,
  type DocChild,
} from './documentText';

export interface SchoolOffersDrawerProps {
  open: boolean;
  offers: SchoolOffer[];
  children: DocChild[];
  onClose: () => void;
  onKeep: (offer: SchoolOffer, section: DocumentSection) => Promise<unknown>;
  onDismiss: (offer: SchoolOffer) => Promise<unknown>;
  /** A report prompt's "Add it here": opens the add drawer filled in from the offer. */
  onAddReport: (offer: SchoolOffer) => void;
}

function OfferCard({
  offer,
  children,
  busy,
  onKeep,
  onDismiss,
  onAddReport,
}: {
  offer: SchoolOffer;
  children: DocChild[];
  busy: boolean;
  onKeep: (section: DocumentSection) => void;
  onDismiss: () => void;
  onAddReport: () => void;
}) {
  const [section, setSection] = useState<DocumentSection>(offer.section);
  const titleId = `school-offer-${offer.id}`;
  const report = offer.kind === 'REPORT_READY';
  const link = report ? safeReportLink(offer.reportLink) : null;
  return (
    <li className="document-offer" aria-labelledby={titleId}>
      <div className="document-offer__head">
        <ChildDots ids={offer.childIds} children={children} />
        <div>
          <h3 id={titleId} className="document-offer__title">{offer.title}</h3>
          <p className="document-offer__meta">
            {offer.sender} · {formatInstant(offer.sentAt)}
            {offer.personal && (
              <span className="document-tag document-tag--violet">
                <Mail size={12} aria-hidden="true" /> Personal letter
              </span>
            )}
          </p>
        </div>
      </div>
      {offer.reason && (
        <p className="document-offer__why">
          <Info size={13} aria-hidden="true" /> {offer.reason}
        </p>
      )}
      {!report && offer.attachmentName && (
        <p className="document-offer__file">
          <span className="document-tag document-tag--slate">
            <FileText size={12} aria-hidden="true" /> {offer.attachmentName}
          </span>
        </p>
      )}
      <div className="document-offer__actions">
        {report ? (
          <>
            {link && (
              <a className="cp-button cp-button--secondary cp-button--sm" href={link} target="_blank" rel="noopener noreferrer">
                Open Insight Tracking <ExternalLink size={14} aria-hidden="true" />
              </a>
            )}
            <button type="button" className="cp-button cp-button--primary cp-button--sm" onClick={onAddReport} disabled={busy}>
              <Plus size={15} aria-hidden="true" /> Add it here
            </button>
          </>
        ) : (
          <>
            <button type="button" className="cp-button cp-button--primary cp-button--sm" onClick={() => onKeep(section)} disabled={busy}>
              Keep in {possessive(offer.childIds, children, false)} {sectionLabel(section)}
            </button>
            <select
              className="document-offer__section"
              aria-label="File under"
              value={section}
              onChange={(event) => setSection(event.target.value as DocumentSection)}
              disabled={busy}
            >
              {SECTIONS.map((entry) => (
                <option key={entry.key} value={entry.key}>{entry.label}</option>
              ))}
            </select>
          </>
        )}
        <button type="button" className="cp-button cp-button--quiet cp-button--sm" onClick={onDismiss} disabled={busy}>
          Not needed
        </button>
      </div>
    </li>
  );
}

/**
 * Letters Term Time found for the family, and report prompts. Nothing is filed until a parent
 * keeps it, and a letter marked "Not needed" is never offered again.
 */
export function SchoolOffersDrawer({
  open,
  offers,
  children,
  onClose,
  onKeep,
  onDismiss,
  onAddReport,
}: SchoolOffersDrawerProps) {
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const names = joinNames(children.map((child) => child.firstName));

  const act = async (id: string, run: () => Promise<unknown>) => {
    setBusy(id);
    setError(null);
    try {
      await run();
    } catch {
      setError('That did not work. Your co-parent may have answered it already; try again.');
    } finally {
      setBusy(null);
    }
  };

  return (
    <DocumentSheet
      open={open}
      title="From school"
      description="Letters from school to keep in the family library"
      subtitle={`Letters Term Time found${names ? ` for ${names}` : ''}. Nothing is filed until you keep it.`}
      leading={(
        <span className="document-sheet__icon" aria-hidden="true">
          <Mail size={18} />
        </span>
      )}
      onClose={onClose}
    >
      {error && (
        <p role="alert" className="document-callout document-callout--rose">
          {error}
        </p>
      )}
      {offers.length === 0 ? (
        <p className="document-empty-note">
          All caught up. New letters{names ? ` for ${names}` : ''} appear here.
        </p>
      ) : (
        <ul className="document-offers">
          {offers.map((offer) => (
            <OfferCard
              key={offer.id}
              offer={offer}
              children={children}
              busy={busy === offer.id}
              onKeep={(section) => void act(offer.id, () => onKeep(offer, section))}
              onDismiss={() => void act(offer.id, () => onDismiss(offer))}
              onAddReport={() => onAddReport(offer)}
            />
          ))}
        </ul>
      )}
    </DocumentSheet>
  );
}
