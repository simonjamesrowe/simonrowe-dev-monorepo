import { useEffect, useState } from 'react';

import { fetchDocumentFile } from '../../hooks/api/useDocuments';

import { downloadName } from './documentText';

/**
 * A stored file as an object URL, fetched with the bearer token. The URL is revoked when the
 * file changes or the component unmounts, so closing a drawer or viewer releases it.
 */
export function useDocumentFileUrl(
  familyId: string | undefined,
  documentId: string | undefined,
  fileId: string | undefined,
): { url: string | null; failed: boolean } {
  const [state, setState] = useState<{ key: string; url: string | null; failed: boolean }>({
    key: '',
    url: null,
    failed: false,
  });
  const key = familyId && documentId && fileId ? `${familyId}/${documentId}/${fileId}` : '';

  useEffect(() => {
    if (!familyId || !documentId || !fileId) return undefined;
    let created: string | null = null;
    let cancelled = false;
    fetchDocumentFile(familyId, documentId, fileId)
      .then((blob) => {
        if (cancelled) return;
        created = URL.createObjectURL(blob);
        setState({ key: `${familyId}/${documentId}/${fileId}`, url: created, failed: false });
      })
      .catch(() => {
        if (!cancelled) setState({ key: `${familyId}/${documentId}/${fileId}`, url: null, failed: true });
      });
    return () => {
      cancelled = true;
      if (created) URL.revokeObjectURL(created);
    };
  }, [familyId, documentId, fileId]);

  // A URL for some other file is never shown while the new one loads.
  return state.key === key ? { url: state.url, failed: state.failed } : { url: null, failed: false };
}

/** Saves a stored file to the device under a name made from the document's title. */
export async function downloadDocumentFile(
  familyId: string,
  documentId: string,
  file: { fileId: string; mimeType: string },
  title: string,
  page?: number,
): Promise<void> {
  const blob = await fetchDocumentFile(familyId, documentId, file.fileId, true);
  const url = URL.createObjectURL(blob);
  try {
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = downloadName(title, file.mimeType, page);
    anchor.rel = 'noopener';
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
  } finally {
    // Revoked on the next tick: some browsers start the download after click() returns.
    setTimeout(() => URL.revokeObjectURL(url), 0);
  }
}
