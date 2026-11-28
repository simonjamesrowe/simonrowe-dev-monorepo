import { ChevronLeft, ChevronRight, Download, X, ZoomIn, ZoomOut } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';

import type { DocumentVersion } from '../../types/documents';

import { isPdf } from './documentText';
import { useDocumentFileUrl } from './useDocumentFileUrl';

/**
 * A document full screen, in the app: photos one page at a time (zoomable, and pinch-zoom on a
 * phone), a PDF in the browser's own viewer. Nothing is kept offline. The page shows it in place
 * of the drawer, because the drawer's modal would otherwise swallow its clicks.
 */
export function DocumentViewer({
  familyId,
  documentId,
  title,
  version,
  onDownload,
  onClose,
}: {
  familyId: string;
  documentId: string;
  title: string;
  version: DocumentVersion;
  onDownload: () => void;
  onClose: () => void;
}) {
  const [page, setPage] = useState(0);
  const [zoomed, setZoomed] = useState(false);
  const closeRef = useRef<HTMLButtonElement>(null);
  const files = version.files;
  const file = files[Math.min(page, files.length - 1)];
  const { url, failed } = useDocumentFileUrl(familyId, documentId, file?.fileId);
  const count = files.length;

  useEffect(() => {
    closeRef.current?.focus();
  }, []);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
      if (event.key === 'ArrowRight') setPage((current) => Math.min(count - 1, current + 1));
      if (event.key === 'ArrowLeft') setPage((current) => Math.max(0, current - 1));
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [count, onClose]);

  const go = (next: number) => {
    setZoomed(false);
    setPage(next);
  };

  return (
    <div className="document-viewer" role="dialog" aria-modal="true" aria-label={`${title}, full screen`}>
      <header className="document-viewer__bar">
        <strong className="document-viewer__title">{title}</strong>
        {count > 1 && (
          <span className="document-viewer__pager">
            <button type="button" className="cp-icon-button cp-icon-button--sm" onClick={() => go(page - 1)} disabled={page === 0} aria-label="Previous page">
              <ChevronLeft size={16} aria-hidden="true" />
            </button>
            <span>Page {page + 1} of {count}</span>
            <button type="button" className="cp-icon-button cp-icon-button--sm" onClick={() => go(page + 1)} disabled={page >= count - 1} aria-label="Next page">
              <ChevronRight size={16} aria-hidden="true" />
            </button>
          </span>
        )}
        <span className="document-viewer__tools">
          {file && !isPdf(file.mimeType) && (
            <button type="button" className="cp-icon-button cp-icon-button--sm" onClick={() => setZoomed(!zoomed)} aria-label={zoomed ? 'Zoom out' : 'Zoom in'} aria-pressed={zoomed}>
              {zoomed ? <ZoomOut size={16} aria-hidden="true" /> : <ZoomIn size={16} aria-hidden="true" />}
            </button>
          )}
          <button type="button" className="cp-icon-button cp-icon-button--sm" onClick={onDownload} aria-label="Download">
            <Download size={16} aria-hidden="true" />
          </button>
          <button ref={closeRef} type="button" className="cp-icon-button cp-icon-button--sm" onClick={onClose} aria-label="Close full screen">
            <X size={16} aria-hidden="true" />
          </button>
        </span>
      </header>
      <div className={`document-viewer__stage${zoomed ? ' is-zoomed' : ''}`}>
        {failed && <p className="document-viewer__note">This page could not be loaded. Try again.</p>}
        {!failed && !url && <p className="document-viewer__note">Loading…</p>}
        {url && file && isPdf(file.mimeType) && (
          <object data={url} type="application/pdf" className="document-viewer__pdf" aria-label={title}>
            <p className="document-viewer__note">
              This browser cannot show the PDF here. Use Download to open it.
            </p>
          </object>
        )}
        {url && file && !isPdf(file.mimeType) && (
          <img
            src={url}
            alt={count > 1 ? `${title}, page ${page + 1}` : title}
            className="document-viewer__img"
            onClick={() => setZoomed(!zoomed)}
          />
        )}
      </div>
    </div>
  );
}
