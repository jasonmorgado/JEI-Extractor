package com.example.examplemod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.IRecipeLookup;
import mezz.jei.api.recipe.RecipeType;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Extracts recipe slots from JEI categories and recipes into JSON format.
 */
public class SlotExtractor {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Iterates through all recipes in all categories, extracting slots to recipe_slots.json
     * @param recipeManager JEI RecipeManager
     * @param outDir Folder to output to
     * @throws IOException Throws this if it cannot write to output file
     */
    public void writeRecipeSlotsFile(IRecipeManager recipeManager, Path outDir) throws IOException {

        // Extract slots from Categories + Recipes
        var slotsByTypeAndRecipe = extractSlotsFromAllCategories(recipeManager);

        // Convert into JSON
        var slotsJsonMap = new LinkedHashMap<>();
        // For RecipeType in Types
        for (var typeEntry : slotsByTypeAndRecipe.entrySet()) {
            var recipesMap = new LinkedHashMap<>();
            // For Recipe in RecipeType
            for (var recipeEntry : typeEntry.getValue().entrySet()) {
                // Get Slots
                var slotMaps = recipeEntry.getValue().stream()
                        .map(CapturedSlot::toMap)
                        .collect(Collectors.toList());
                recipesMap.put(recipeEntry.getKey(), slotMaps);
            }
            slotsJsonMap.put(typeEntry.getKey(), recipesMap);
        }

        // Write slots to JSON file
        var slotsFile = outDir.resolve("recipe_slots.json");
        var slotsContent = GSON.toJson(slotsJsonMap);
        Files.writeString(slotsFile, slotsContent);
        LOGGER.info("Wrote slots for {} recipe types to {}", slotsJsonMap.size(), slotsFile.getFileName());
    }

    /**
     * Given the RecipeManager, extracts JSON of the slots, returns them
     *
     * @param recipeManager - The JEI Recipe Manager
     * @return Map of slots
     * craftingType -> recipe_id -> List<Slot>
     * {
     *   "crafting_type":{
     *     "recipe_id": [
     *       "role": "INPUT"/"OUTPUT,
     *       "x": int,
     *       "y": int,
     *       "items": ["1 cobblestone"],
     *       "fluids": []
     *     ]
     *   }
     * }
     */
    private Map<String, Map<String, List<CapturedSlot>>> extractSlotsFromAllCategories(IRecipeManager recipeManager) {
        Map<String, Map<String, List<CapturedSlot>>> slotsMap = new LinkedHashMap<>();

        List<IRecipeCategory<?>> categories = recipeManager.createRecipeCategoryLookup()
                .get()
                .toList();

        for (IRecipeCategory<?> category : categories) {
            RecipeType<?> recipeType = category.getRecipeType();
            String typeId = recipeType.getUid().toString();
            IRecipeLookup<?> recipeLookup = recipeManager.createRecipeLookup(recipeType);

            recipeLookup.get().forEach(recipe -> {
                Optional<List<CapturedSlot>> slots = captureSlotsFromRecipe(category, recipe);
                if (slots.isPresent()) {
                    String recipeId = new RecipeScraper().getRecipeId(recipe);
                    slotsMap.computeIfAbsent(typeId, k -> new LinkedHashMap<>()).put(recipeId, slots.get());
                }
            });
        }

        return slotsMap;
    }

    /**
     * Captures Slots from a recipe category and recipe.
     * @param category the RecipeCategory
     * @param recipe the Recipe itself
     * @return Optional containing the list of captured slots, or empty if capture failed
     */
    @SuppressWarnings("unchecked")
    private Optional<List<CapturedSlot>> captureSlotsFromRecipe(IRecipeCategory<?> category, Object recipe) {
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
