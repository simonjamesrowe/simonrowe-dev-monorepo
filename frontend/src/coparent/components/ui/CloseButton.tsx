import { X } from 'lucide-react';

/**
 * The close button every CoParent drawer and dialog uses: the bordered `cp-icon-button` square
 * with an X. The label is what a screen reader hears, so name what closes when there is more
 * than one thing open ("Close child editor").
 */
export function CloseButton({ onClick, label = 'Close' }: { onClick: () => void; label?: string }) {
  return (
    <button type="button" className="cp-icon-button" onClick={onClick} aria-label={label}>
      <X size={18} aria-hidden="true" />
    </button>
  );
}
