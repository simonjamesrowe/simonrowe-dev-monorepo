package com.simonrowe.meals;

import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One of Dave's weeks: the recipes a meal-plan week uses, offered as a starting point for Up
 * next. The calorie figure is the book's label for the week and nothing in the app uses it.
 *
 * @param id stable id, e.g. {@code mp1-1500-w3}
 * @param label display label
 * @param source which meal-plan book
 * @param kcal the book's daily calorie level for the week
 * @param week week number within that level
 * @param recipeIds the recipes the week uses
 */
@Document(collection = MealTemplate.COLLECTION)
public record MealTemplate(
    @Id String id,
    String label,
    String source,
    int kcal,
    int week,
    List<String> recipeIds
) {

  public static final String COLLECTION = "meal_plan_templates";
}
