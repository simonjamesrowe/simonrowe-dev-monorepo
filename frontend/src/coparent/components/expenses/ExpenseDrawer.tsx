import { X } from 'lucide-react';
import type { ReactNode } from 'react';
import { Drawer } from 'vaul';

/**
 * The right-hand drawer every expense screen uses. Vaul portals to <body>, outside the
 * .coparent-app scope the styles live under, so the wrapper is required, not decoration.
 */
export function ExpenseDrawer({
  open,
  title,
  description,
  eyebrow,
  onClose,
  footer,
  children,
}: {
  open: boolean;
  title: string;
  description: string;
  eyebrow?: string;
  onClose: () => void;
  footer?: ReactNode;
  children: ReactNode;
}) {
  return (
    <Drawer.Root open={open} onOpenChange={(next) => !next && onClose()} direction="right">
      <Drawer.Portal>
        <div className="coparent-app">
          <Drawer.Overlay className="expense-drawer__overlay" />
          <Drawer.Content className="expense-drawer">
            <header className="expense-drawer__head">
              <div>
                {eyebrow && <p className="expense-eyebrow expense-eyebrow--teal">{eyebrow}</p>}
                <Drawer.Title className="expense-drawer__title">{title}</Drawer.Title>
                <Drawer.Description className="sr-only">{description}</Drawer.Description>
              </div>
              <button type="button" className="expense-icon-button" onClick={onClose} aria-label="Close">
                <X size={18} />
              </button>
            </header>
            <div className="expense-drawer__body">{children}</div>
            {footer && <footer className="expense-drawer__foot">{footer}</footer>}
          </Drawer.Content>
        </div>
      </Drawer.Portal>
    </Drawer.Root>
  );
}
