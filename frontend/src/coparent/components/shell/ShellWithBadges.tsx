import { useExpenseSummary, useFamilies } from '../../hooks/api';

import { AppShell, type AppShellProps } from './AppShell';

/**
 * The shell, with counts of what is waiting on the signed-in parent beside the nav items.
 * Rendered inside the protected route, so these queries only run once someone is signed in.
 */
export function ShellWithBadges(props: AppShellProps) {
  const { data: families = [] } = useFamilies();
  const { data: summary } = useExpenseSummary(families[0]?.id);
  const navigationItems = props.navigationItems.map((item) =>
    item.href === '/expenses' ? { ...item, badge: summary?.needsYourAction || undefined } : item,
  );
  return <AppShell {...props} navigationItems={navigationItems} />;
}
