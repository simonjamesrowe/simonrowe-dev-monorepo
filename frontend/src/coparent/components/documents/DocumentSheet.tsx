import type { ReactNode } from 'react';
import { Drawer } from 'vaul';

import { useMediaQuery } from '../../../hooks/useMediaQuery';
import { CloseButton } from '../ui/CloseButton';

/**
 * The drawer every documents screen uses: from the right on a wide screen, a bottom sheet on a
 * phone. Vaul portals to <body>, outside the .coparent-app scope the styles live under, so the
 * wrapper is required, not decoration.
 */
export function DocumentSheet({
  open,
  title,
  description,
  subtitle,
  leading,
  onClose,
  footer,
  children,
}: {
  open: boolean;
  title: string;
  /** Read to a screen reader only. */
  description: string;
  /** Shown under the title. */
  subtitle?: ReactNode;
  /** An icon or child dot before the title. */
  leading?: ReactNode;
  onClose: () => void;
  footer?: ReactNode;
  children: ReactNode;
}) {
  const phone = useMediaQuery('(max-width: 767px)');
  return (
    <Drawer.Root open={open} onOpenChange={(next) => !next && onClose()} direction={phone ? 'bottom' : 'right'}>
      <Drawer.Portal>
        <div className="coparent-app">
          <Drawer.Overlay className="document-sheet__overlay" />
          <Drawer.Content className={`document-sheet${phone ? ' document-sheet--bottom' : ''}`}>
            <header className="document-sheet__head">
              {leading}
              <div className="document-sheet__heading">
                <Drawer.Title className="document-sheet__title">{title}</Drawer.Title>
                {subtitle && <p className="document-sheet__sub">{subtitle}</p>}
                <Drawer.Description className="sr-only">{description}</Drawer.Description>
              </div>
              <CloseButton onClick={onClose} />
            </header>
            {/* Scrolling the body must never drag the sheet shut. */}
            <div className="document-sheet__body" data-vaul-no-drag>
              {children}
            </div>
            {footer && <footer className="document-sheet__foot">{footer}</footer>}
          </Drawer.Content>
        </div>
      </Drawer.Portal>
    </Drawer.Root>
  );
}
