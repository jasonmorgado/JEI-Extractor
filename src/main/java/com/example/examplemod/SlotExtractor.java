package com.example.examplemod;

import com.mojang.logging.LogUtils;
import mezz.jei.api.recipe.category.IRecipeCategory;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Captures slot positions from JEI recipe categories.
 */
public class SlotExtractor {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Captures Slots from a recipe category and recipe.
     * @param category the RecipeCategory
     * @param recipe the Recipe itself
     * @return Optional containing the list of captured slots, or empty if capture failed
     */
    @SuppressWarnings("unchecked")
    public Optional<List<CapturedSlot>> captureSlotsFromRecipe(IRecipeCategory<?> category, Object recipe) {
        CapturingLayoutBuilder layoutBuilder = new CapturingLayoutBuilder();
        try {
            ((IRecipeCategory<Object>) category).setRecipe(layoutBuilder, recipe, null);
            return Optional.of(layoutBuilder.getSlots());
        } catch (Exception e) {
            LOGGER.warn("Failed to capture slots for recipe: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
