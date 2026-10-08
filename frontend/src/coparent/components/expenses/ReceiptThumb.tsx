import { FileText, X } from 'lucide-react';
import { useEffect, useState } from 'react';

import { fetchReceiptBlob } from '../../hooks/api';
import type { ExpenseReceipt } from '../../types/expenses';

/**
 * A stored receipt, fetched with the bearer token and shown through an object URL. The URL is
 * revoked on unmount, so closing a drawer releases every receipt it displayed.
 */
export function StoredReceiptThumb({
  familyId,
  expenseId,
  receipt,
  onOpen,
  onRemove,
}: {
  familyId: string;
  expenseId: string;
  receipt: ExpenseReceipt;
  onOpen: (url: string, receipt: ExpenseReceipt) => void;
  onRemove?: () => void;
}) {
  const [url, setUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let created: string | null = null;
    let cancelled = false;
    fetchReceiptBlob(familyId, expenseId, receipt.id)
      .then((blob) => {
        if (cancelled) return;
        created = URL.createObjectURL(blob);
        setUrl(created);
      })
      .catch(() => !cancelled && setFailed(true));
    return () => {
      cancelled = true;
      if (created) URL.revokeObjectURL(created);
    };
  }, [familyId, expenseId, receipt.id]);

  const pdf = receipt.contentType === 'application/pdf';
  return (
    <span className="expense-thumb-wrap">
      <button
        type="button"
        className={`expense-thumb${pdf ? ' expense-thumb--pdf' : ''}`}
        onClick={() => url && onOpen(url, receipt)}
        disabled={!url}
        aria-label={`Open receipt ${receipt.displayName}`}
      >
        {pdf || !url ? (
          <>
            <FileText size={20} aria-hidden="true" />
            <span>{failed ? 'Missing' : pdf ? 'PDF' : '…'}</span>
          </>
        ) : (
          <img src={url} alt="" />
        )}
      </button>
      {onRemove && (
        <button type="button" className="expense-thumb__remove" onClick={onRemove} aria-label={`Remove ${receipt.displayName}`}>
          <X size={12} />
        </button>
      )}
    </span>
  );
}

/** A file chosen in the form but not yet uploaded. */
export function PendingReceiptThumb({
  file,
  previewUrl,
  onRemove,
}: {
  file: File;
  previewUrl: string | null;
  onRemove: () => void;
}) {
  return (
    <span className="expense-thumb-wrap">
      <span className={`expense-thumb${previewUrl ? '' : ' expense-thumb--pdf'}`}>
        {previewUrl ? <img src={previewUrl} alt="" /> : (<><FileText size={20} aria-hidden="true" /><span>PDF</span></>)}
      </span>
      <button type="button" className="expense-thumb__remove" onClick={onRemove} aria-label={`Remove ${file.name}`}>
        <X size={12} />
      </button>
    </span>
  );
}

/** Full-size view of a receipt, over the drawer. */
export function ReceiptLightbox({
  receipt,
  url,
  onClose,
}: {
  receipt: ExpenseReceipt;
  url: string;
  onClose: () => void;
}) {
  return (
    <div className="expense-lightbox" role="dialog" aria-label={receipt.displayName} onClick={onClose}>
      <div className="expense-lightbox__inner">
        {receipt.contentType === 'application/pdf' ? (
          <iframe title={receipt.displayName} src={url} className="expense-lightbox__pdf" sandbox="" />
        ) : (
          <img src={url} alt={receipt.displayName} />
        )}
        <span className="expense-muted">{receipt.displayName} · tap anywhere to close</span>
      </div>
    </div>
  );
}
