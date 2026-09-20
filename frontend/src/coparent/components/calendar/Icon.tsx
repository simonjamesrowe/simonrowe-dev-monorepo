import {
  Home,
  HeartPulse,
  GraduationCap,
  Trophy,
  Cake,
  Circle,
  Music,
  Calendar,
  CalendarDays,
  Clock,
  MapPin,
  User,
  Users,
  Plus,
  X,
  Check,
  ChevronLeft,
  ChevronRight,
  MoreHorizontal,
  Edit,
  Trash2,
  AlertCircle,
  Info,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';

const iconMap: Record<string, LucideIcon> = {
  home: Home,
  'heart-pulse': HeartPulse,
  'graduation-cap': GraduationCap,
  trophy: Trophy,
  cake: Cake,
  circle: Circle,
  music: Music,
  calendar: Calendar,
  'calendar-days': CalendarDays,
  clock: Clock,
  'map-pin': MapPin,
  user: User,
  users: Users,
  plus: Plus,
  x: X,
  check: Check,
  'chevron-left': ChevronLeft,
  'chevron-right': ChevronRight,
  'more-horizontal': MoreHorizontal,
  edit: Edit,
  'trash-2': Trash2,
  'alert-circle': AlertCircle,
  info: Info,
};

interface IconProps {
  name: string;
  className?: string;
  size?: number;
}

export function Icon({ name, className = '', size = 16 }: IconProps) {
  const IconComponent = iconMap[name] || Circle;
  return <IconComponent className={className} size={size} />;
}
