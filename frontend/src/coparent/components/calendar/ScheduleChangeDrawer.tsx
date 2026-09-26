import { X } from 'lucide-react';
import { Drawer } from 'vaul';

import { ScheduleChangeApproval, type ScheduleChangeApprovalProps } from './ScheduleChangeApproval';

export interface ScheduleChangeDrawerProps extends ScheduleChangeApprovalProps {
  open: boolean;
  onClose: () => void;
}

/**
 * Hosts the schedule-change requests beside the calendar. The approval view existed but was never
 * rendered anywhere, so a pending request could be counted on the badge and never answered.
 */
export function ScheduleChangeDrawer({ open, onClose, ...approval }: ScheduleChangeDrawerProps) {
  return (
    <Drawer.Root open={open} onOpenChange={(next) => !next && onClose()} direction="right">
      <Drawer.Portal>
        {/* Utilities are scoped to .coparent-app and Vaul portals to <body>, outside it. */}
        <div className="coparent-app">
          <Drawer.Overlay className="fixed inset-0 z-40 bg-black/40" />
          <Drawer.Content className="fixed bottom-0 right-0 top-0 z-50 w-full max-w-none bg-white shadow-xl outline-none sm:w-5/6 md:w-4/5 lg:w-3/4 dark:bg-slate-900">
            <Drawer.Title className="sr-only">Schedule change requests</Drawer.Title>
            <Drawer.Description className="sr-only">
              Review, approve, decline or withdraw requests to change the schedule
            </Drawer.Description>
            <div className="flex h-full flex-col">
              <div className="flex items-center justify-between border-b border-slate-200 px-6 py-4 dark:border-slate-700">
                <h2 className="text-xl font-semibold text-slate-900 dark:text-slate-100">
                  Change requests
                </h2>
                <button
                  onClick={onClose}
                  className="rounded-lg p-2 text-slate-500 transition-colors hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-slate-800"
                  aria-label="Close"
                >
                  <X size={20} />
                </button>
              </div>
              <div className="flex-1 overflow-y-auto" data-vaul-no-drag>
                <ScheduleChangeApproval {...approval} />
              </div>
            </div>
          </Drawer.Content>
        </div>
      </Drawer.Portal>
    </Drawer.Root>
  );
}
