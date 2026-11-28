package com.simonrowe.coparent.school;

import com.simonrowe.coparent.model.Child;
import com.simonrowe.school.model.SchoolEvent;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Decides which of a family's linked children a school event is for. Rules, not a model: who an
 * event is for is published by the school (a year group, a class), and a guess here would put
 * another class's trip on a child's calendar under the school's name.
 *
 * <ol>
 *   <li>The child's year group on the event's date must be one the event names: the source's
 *       own tags, or, for an untagged event, the years its title names ("Year 3 Local Area
 *       Walk"). An event naming no year is whole-school.</li>
 *   <li>An event whose title names a class ("Elis's class trip") is for that class only. The
 *       school files these as whole-school more often than not, so without this rule every
 *       class's trip would reach every child. A child whose class is not known (never entered,
 *       or entered last school year) gets a class event only when the event names their year
 *       group, so a parent sees both of their year's classes rather than neither.</li>
 * </ol>
 */
final class SchoolEventMatcher {

  private SchoolEventMatcher() {
  }

  /**
   * The children an event is for.
   *
   * @param event a school event
   * @param children a family's linked children
   * @return the children it is for, in the order given; empty when it is for none of them
   */
  static List<Child> childrenFor(final SchoolEvent event, final List<Child> children) {
    return children.stream().filter(child -> isFor(event, child)).toList();
  }

  /**
   * Whether an event is for one child.
   *
   * @param event a school event
   * @param child a linked child
   * @return true when the event is for them
   */
  static boolean isFor(final SchoolEvent event, final Child child) {
    return isFor(event.startDate(), event.yearGroups(), event.title(), child);
  }

  /**
   * Whether something the school sent on a date, tagged for some year groups and titled as
   * given, is for one child. The same rules as for an event, so a letter reaches exactly the
   * children its event would ({@code SchoolDocumentOfferSync}).
   *
   * @param on the date to work out the child's year and class for
   * @param yearGroups the year groups the school tagged it with; empty when untagged
   * @param title its title, which may name year groups or a class
   * @param child a linked child
   * @return true when it is for them
   */
  static boolean isFor(final LocalDate on, final List<String> yearGroups, final String title,
      final Child child) {
    final Optional<String> year = ChildSchoolYear.yearGroupOf(child, on);
    if (year.isEmpty()) {
      return false;
    }
    final List<String> tagged = yearGroups == null ? List.of() : yearGroups;
    final List<String> years = tagged.isEmpty()
        ? List.copyOf(SchoolEventText.yearGroupsNamed(title))
        : tagged;
    final boolean yearNamed = years.contains(year.get());
    if (!years.isEmpty() && !yearNamed) {
      return false;
    }
    final Optional<String> ownClass = ChildSchoolYear.classOf(child, on);
    if (ownClass.isPresent() && SchoolEventText.namesClass(title, ownClass.get())) {
      return true;
    }
    final Set<String> classes = SchoolEventText.classesNamed(title);
    if (classes.isEmpty()) {
      return true;
    }
    // Another class's event, or a class event for a child whose class is not known.
    return ownClass.isEmpty() && yearNamed;
  }
}
