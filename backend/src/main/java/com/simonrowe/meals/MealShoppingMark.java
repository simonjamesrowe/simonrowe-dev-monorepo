package com.simonrowe.meals;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Shopping state the list itself cannot work out: something added by hand ({@code extra}), and
 * which online shop's basket an item has gone into. The list's lines are otherwise derived from
 * Up next and the kitchen every time.
 *
 * @param id {@code <ownerId>|<name>}
 * @param ownerId Auth0 subject
 * @param name ingredient or free-text item
 * @param extra added by hand rather than needed by a recipe
 * @param shop Ocado, Amazon or Deliveroo once in a basket, otherwise null
 * @param markedAt when it last changed
 */
@Document(collection = MealShoppingMark.COLLECTION)
public record MealShoppingMark(
    @Id String id,
    String ownerId,
    String name,
    boolean extra,
    String shop,
    Instant markedAt
) {

  public static final String COLLECTION = "meal_shopping";

  public static String idFor(final String ownerId, final String name) {
    return ownerId + "|" + name;
  }
}
