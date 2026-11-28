import { useExpenseSummary, useFamilies, useSchoolOffers } from '../../hooks/api';

import { AppShell, type AppShellProps } from './AppShell';

/**
 * The shell, with counts of what is waiting on the signed-in parent beside the nav items.
 * Rendered inside the protected route, so these queries only run once someone is signed in.
 */
export function ShellWithBadges(props: AppShellProps) {
  const { data: families = [] } = useFamilies();
  const familyId = families[0]?.id;
  const { data: summary } = useExpenseSummary(familyId);
  // Letters from school waiting to be kept or dismissed.
  const { data: offers } = useSchoolOffers(familyId);
  const navigationItems = props.navigationItems.map((item) => {
    if (item.href === '/expenses') return { ...item, badge: summary?.needsYourAction || undefined };
    if (item.href === '/documents') return { ...item, badge: offers?.length || undefined };
    return item;
  });
  return <AppShell {...props} navigationItems={navigationItems} />;
}
