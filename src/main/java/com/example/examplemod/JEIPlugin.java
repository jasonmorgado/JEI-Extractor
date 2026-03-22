package com.example.examplemod;

import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.recipe.IRecipeLookup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.category.IRecipeCategory;

import java.util.*;
import java.util.stream.Collectors;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

@JeiPlugin
public class JEIPlugin implements IModPlugin {

    private static final Logger LOGGER = LogUtils.getLogger();

    private Map<String, List<String>> ingredientsMap = new HashMap<>();
    private Map<String, String> ingredientLookup = new HashMap<>();  // maps sorted item names to ingredient ID
    private int ingredientCounter = 1;

    @Override
    public ResourceLocation getPluginUid() {
        return new ResourceLocation(ExampleMod.MODID, "jei_plugin");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        // Runs when the world is loaded and JEI's recipe manager is available
        IRecipeManager recipeManager = jeiRuntime.getRecipeManager();
        
        Collection<RecipeType<?>> recipeTypes = recipeManager.createRecipeCategoryLookup()
                .get()
                .map(IRecipeCategory::getRecipeType)
                .collect(Collectors.toList());

        Path outDir = Paths.get("../out");
        try {
            Files.createDirectories(outDir);

            // Write recipe types file
            Path typesFile = outDir.resolve("recipe_types.txt");
            Files.writeString(typesFile, recipeTypes.stream()
                    .map(RecipeType::toString)
                    .collect(Collectors.joining("\n")));
            LOGGER.info("JEI Recipe Types written to {}", typesFile.toAbsolutePath());

            // Write per-type recipe files
            for (RecipeType<?> type : recipeTypes) {
                writeRecipesForType(recipeManager, outDir, type);
            }

            // Write ingredients file
            writeIngredientsFile(outDir);
        } catch (IOException e) {
            LOGGER.error("Failed to write recipe types: {}", e.getMessage());
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private <R> void writeRecipesForType(IRecipeManager recipeManager, Path outDir, RecipeType<R> type) {
        // Given a RecipeType, extract all recipes of that type and write to a JSON file
        try {
            String fileName = type.getUid().getPath() + ".json";
            Path file = outDir.resolve(fileName);

            IRecipeLookup<R> recipeLookup = recipeManager.createRecipeLookup(type);
            var recipe = recipeLookup.get().findFirst().orElse(null);

            List<Map<String, Object>> recipes = recipeManager.createRecipeLookup(type)
                    .get()
                    .map(this::recipeToMap)
                    .collect(Collectors.toList());

            String content = GSON.toJson(recipes);
            Files.writeString(file, content);
            LOGGER.info("Wrote {} recipes to {}", recipes.size(), file.getFileName());
        } catch (IOException e) {
            LOGGER.warn("Failed to write recipes for type {}: {}", type.getUid(), e.getMessage());
        }
    }

    /**
     * Converts a Recipe object to a map of simple key-value pairs.
     */
    private Map<String, Object> recipeToMap(Object recipe) {
        String recipeType = recipe.getClass().getSimpleName();

        if (recipeType.equals("ShapedRecipe")) {
            return shapedRecipeToMap((ShapedRecipe) recipe);
        }

        Map<String, Object> map = new HashMap<>();
        map.put("_type", recipeType);

        try {
            java.lang.reflect.Field[] fields = recipe.getClass().getDeclaredFields();
            for (java.lang.reflect.Field field : fields) {
                field.setAccessible(true);
                Object value = field.get(recipe);
                map.put(field.getName(), String.valueOf(value));
            }
        } catch (Exception e) {
            map.put("_error", e.getMessage());
        }

        return map;
    }

    private Map<String, Object> shapedRecipeToMap(ShapedRecipe recipe) {
        Map<String, Object> map = new HashMap<>();

        // id is ResourceLocation with namespace, path properties like minecraft:piston
        map.put("id", recipe.getId().toString());

        // Standard properties
        map.put("width", recipe.getWidth());
        map.put("height", recipe.getHeight());
        map.put("group", recipe.getGroup());
        map.put("_type", "ShapedRecipe");

        map.put(
        "result_count",
            recipe.getResultItem().getCount()
        );

        // Add recipe ingredients
        List<String> ingredientIds = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            // Given the Ingredient Object, has Values like tags determining what could be used
            // Also has itemStacks determining individual options
            System.out.println(ingredient.toString());
            // Values / Tags
            // Throws an error about JeiIngredient
            // JsonElement ingredientJson = ingredient.toJson();
            // map.put("ingredientJson", ingredientJson);

            // System.out.println(ingredientJson);

            // ItemStack individual Options
            List<String> itemNames = new ArrayList<>();
            for (ItemStack itemStack : ingredient.getItems()) {
                Item item = itemStack.getItem();
                itemNames.add(item.toString());
            }
            // Get or create ingredient ID for this set of items
            String ingredientId = getOrCreateIngredientId(itemNames);
            ingredientIds.add(ingredientId);
        }
        map.put("ingredients", ingredientIds);

        return map;
    }

    private String getOrCreateIngredientId(List<String> itemNames) {
        // If empty, use empty string (don't save to map)
        if (itemNames.isEmpty()) {
            return "";
        }

        // If only one item, use its name as the ID (don't save to map)
        if (itemNames.size() == 1) {
            return itemNames.get(0);
        }

        // Multi-item ingredients: save to map
        List<String> sorted = new ArrayList<>(itemNames);
        Collections.sort(sorted);
        String key = String.join(",", sorted);

        if (ingredientLookup.containsKey(key)) {
            return ingredientLookup.get(key);
        }

        String id = "ingredient_" + ingredientCounter++;
        ingredientLookup.put(key, id);
        ingredientsMap.put(id, itemNames);
        return id;
    }

    private void writeIngredientsFile(Path outDir) throws IOException {
        Path file = outDir.resolve("ingredients.json");
        String content = GSON.toJson(ingredientsMap);
        Files.writeString(file, content);
        LOGGER.info("Wrote {} ingredients to {}", ingredientsMap.size(), file.getFileName());
    }

}
