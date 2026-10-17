package com.simonrowe.meals;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Up next and the shopping list built from it. */
@RestController
@RequestMapping("/api/meals")
public class MealsPlanController {

  private final KitchenService kitchen;

  public MealsPlanController(final KitchenService kitchen) {
    this.kitchen = kitchen;
  }

  @GetMapping("/up-next")
  public List<MealUpNextItem> upNext(@AuthenticationPrincipal final Jwt jwt) {
    return kitchen.upNext(jwt.getSubject());
  }

  /** Adds a recipe to Up next, or changes its portions. */
  @PutMapping("/up-next/{recipeId}")
  public MealUpNextItem queue(@AuthenticationPrincipal final Jwt jwt,
      @PathVariable final String recipeId, @RequestBody(required = false) final Portions body) {
    return kitchen.queue(jwt.getSubject(), recipeId, body == null ? null : body.portions());
  }

  /**
   * How many portions to make.
   *
   * @param portions 1 to 50, or null for the recipe's own servings
   */
  public record Portions(Integer portions) {
  }

  @DeleteMapping("/up-next/{recipeId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void unqueue(@AuthenticationPrincipal final Jwt jwt,
      @PathVariable final String recipeId) {
    kitchen.unqueue(jwt.getSubject(), recipeId);
  }

  /** Adds every recipe of one of Dave's weeks. */
  @PostMapping("/up-next/templates/{templateId}")
  public List<MealUpNextItem> queueTemplate(@AuthenticationPrincipal final Jwt jwt,
      @PathVariable final String templateId) {
    return kitchen.queueTemplate(jwt.getSubject(), templateId);
  }

  @GetMapping("/shopping")
  public KitchenService.ShoppingList shopping(@AuthenticationPrincipal final Jwt jwt) {
    return kitchen.shoppingList(jwt.getSubject());
  }

  /** Marks an item as in a shop's basket ({@code shop} null takes it out again). */
  @PutMapping("/shopping/basket/{name}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void basket(@AuthenticationPrincipal final Jwt jwt, @PathVariable final String name,
      @RequestBody final Basket body) {
    kitchen.markInBasket(jwt.getSubject(), name, body.shop());
  }

  /**
   * Which basket.
   *
   * @param shop Ocado, Amazon or Deliveroo, or null for none
   */
  public record Basket(String shop) {
  }

  @PostMapping("/shopping/extras")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void addExtra(@AuthenticationPrincipal final Jwt jwt, @RequestBody final Extra body) {
    kitchen.addExtra(jwt.getSubject(), body.name());
  }

  /**
   * Something to buy that no recipe needs.
   *
   * @param name what it is
   */
  public record Extra(String name) {
  }

  @DeleteMapping("/shopping/extras/{name}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeExtra(@AuthenticationPrincipal final Jwt jwt, @PathVariable final String name) {
    kitchen.removeExtra(jwt.getSubject(), name);
  }

  /** Everything in a basket has arrived: put it in the kitchen. */
  @PostMapping("/shopping/delivered")
  public List<MealInventoryItem> delivered(@AuthenticationPrincipal final Jwt jwt) {
    return kitchen.delivered(jwt.getSubject());
  }

  /** Clears the basket marks after a pasted order was read into the kitchen instead. */
  @DeleteMapping("/shopping/basket")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void clearBasket(@AuthenticationPrincipal final Jwt jwt) {
    kitchen.clearBasket(jwt.getSubject());
  }
}
