/** What a calendar event made from a school event holds. */
export interface SchoolEventDetails {
  title: string;
  startDate: string;
  endDate: string;
  /** HH:mm, or null for all day. */
  startTime: string | null;
  endTime: string | null;
  location: string | null;
  notes: string | null;
  /** The school's own wording of the time. */
  sourceTime: string | null;
}

/** A school event offered for the family calendar. */
export interface SchoolSuggestion {
  id: string;
  status: 'pending' | 'accepted' | 'dismissed' | 'withdrawn';
  /** For an accepted event the school has since changed or removed. */
  review: 'changed' | 'removed' | null;
  childIds: string[];
  details: SchoolEventDetails;
  /** For a changed event: the details as the school now gives them. */
  proposed: SchoolEventDetails | null;
  /** The school's titles for the rows this stands for. */
  sourceTitles: string[];
  calendarEventId: string | null;
}

export type SchoolReviewAction = 'APPLY' | 'KEEP' | 'REMOVE';
