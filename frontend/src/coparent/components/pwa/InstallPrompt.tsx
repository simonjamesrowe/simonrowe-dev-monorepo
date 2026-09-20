/**
 * Component to prompt PWA installation
 */

import { useState } from 'react';

import { usePWAInstall } from '../../lib/pwa/usePWAInstall';

export function InstallPrompt() {
  const { canInstall, isInstalled, promptInstall } = usePWAInstall();
  const [dismissed, setDismissed] = useState(false);

  if (!canInstall || isInstalled || dismissed) {
    return null;
  }

  const handleInstall = async () => {
    const installed = await promptInstall();
    if (!installed) {
      setDismissed(true);
    }
  };

  return (
    <div className="fixed bottom-4 left-4 right-4 z-50 md:left-auto md:right-4 md:w-96">
      <div className="rounded-lg bg-gradient-to-r from-teal-500 to-teal-600 p-4 text-white shadow-lg">
        <div className="flex items-start gap-3">
          <div className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-full bg-white/20">
            <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 18h.01M8 21h8a2 2 0 002-2V5a2 2 0 00-2-2H8a2 2 0 00-2 2v14a2 2 0 002 2z"
              />
            </svg>
          </div>

          <div className="flex-1">
            <h3 className="mb-1 font-semibold">Install CoParent</h3>
            <p className="mb-3 text-sm text-white/90">
              Install our app for quick access and offline functionality. Works just like a native
              app!
            </p>

            <div className="flex gap-2">
              <button
                onClick={handleInstall}
                className="rounded-lg bg-white px-4 py-2 text-sm font-medium text-teal-600 transition-colors hover:bg-white/90"
              >
                Install
              </button>
              <button
                onClick={() => setDismissed(true)}
                className="rounded-lg bg-white/20 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-white/30"
              >
                Not Now
              </button>
            </div>
          </div>

          <button
            onClick={() => setDismissed(true)}
            className="flex-shrink-0 rounded-lg p-1 transition-colors hover:bg-white/10"
            aria-label="Close"
          >
            <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M6 18L18 6M6 6l12 12"
              />
            </svg>
          </button>
        </div>
      </div>
    </div>
  );
}
