import { createContext, useContext } from 'react';

/** Lets a page open the shell's Quick add drawer, when the assistant is switched on. */
export interface QuickAddControls {
  available: boolean;
  open: () => void;
}

export const QuickAddContext = createContext<QuickAddControls>({ available: false, open: () => {} });

export function useQuickAdd(): QuickAddControls {
  return useContext(QuickAddContext);
}
