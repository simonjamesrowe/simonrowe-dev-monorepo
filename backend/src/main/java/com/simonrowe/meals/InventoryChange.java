package com.simonrowe.meals;

import java.time.LocalDate;

/**
 * One proposed or applied change to the kitchen.
 *
 * @param name canonical ingredient name
 * @param action add (bought more), use (used some), set (this much is left), status (a staple
 *     is have / low / out) or remove (forget the row)
 * @param quantity amount for add, use and set, in {@code unit}
 * @param unit g, ml or each
 * @param status have, low or out, for status
 * @param useBy use-by date, when known
 * @param source the words the change came from, when a model proposed it
 */
public record InventoryChange(
    String name,
    String action,
    Double quantity,
    String unit,
    String status,
    LocalDate useBy,
    String source
) {
}
