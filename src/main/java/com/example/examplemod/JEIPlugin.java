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
import net.minecraft.world.item.crafting.Recipe;
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

        List<IRecipeCategory<?>> categories = recipeManager.createRecipeCategoryLookup()
                .get()
                .toList();

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
            Map<String, Map<String, Object>> onePerType = new LinkedHashMap<>();
            for (RecipeType<?> type : recipeTypes) {
                // Collect first recipe of each type
                var recipeLookup = recipeManager.createRecipeLookup(type);
                var firstRecipe = recipeLookup.get().findFirst();
                if (firstRecipe.isPresent()) {
                    Map<String, Object> recipeMap = objectToMap(firstRecipe.get());
                    onePerType.put(type.getUid().toString(), recipeMap);
                }

                 writeRecipesForType(recipeManager, outDir, type);
            }

            // Write one_per_type.json
            Path onePerTypeFile = outDir.resolve("one_per_type.json");
            String onePerTypeContent = GSON.toJson(onePerType);
            Files.writeString(onePerTypeFile, onePerTypeContent);
            LOGGER.info("Wrote one recipe per type to {}", onePerTypeFile.getFileName());

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
                    .map(this::objectToMap)
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
    private Map<String, Object> objectToMap(Object obj) {
        String objType = obj.getClass().getSimpleName();

        if (obj instanceof ItemStack) {
            return itemStackToMap((ItemStack) obj);
        }

        if (obj instanceof ShapedRecipe) {
            return shapedRecipeToMap((ShapedRecipe) obj);
        }

        Map<String, Object> map = new HashMap<>();
        map.put("_type", objType);

        try {
            java.lang.reflect.Field[] fields = getAllFields(obj.getClass());
            for (java.lang.reflect.Field field : fields) {
                // Hackily scrape properties of the object
                field.setAccessible(true);
                Object value = field.get(obj);
                String fieldName = field.getName();

                // Depending on the type, we may want to handle differently
                if (value == null){
                    map.put(fieldName, null);
                } else if (value instanceof List<?> list) {
                    List<Map<String, Object>> items = new ArrayList<>();
                    for (Object item : list) {
                        items.add(objectToMap(item));
                    }
                    map.put(field.getName(), items);
                } else if (value instanceof ItemStack) {
                    map.put(fieldName, itemStackToMap((ItemStack) value));
                } else if (value instanceof Ingredient) {
                    map.put(fieldName, ingredientToMap((Ingredient) value));
                } else {
                    map.put(fieldName, String.valueOf(value));
                }
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

    private Map<String, Object> itemStackToMap(ItemStack itemStack) {
        // ItemStack requires special scraping, since it has other ItemStacks as properties of itself
        // Infinite Recursion :(
        Map<String, Object> map = new HashMap<>();
        map.put("_type", "ItemStack");
        map.put("count", itemStack.getCount());
        map.put("item", itemStack.getItem().toString());
        map.put("tag", itemStack.getTag() != null ? parseJsonOrString(itemStack.getTag().toString()) : null);
        map.put("capNBT", parseJsonOrString(itemStack.serializeNBT().toString()));
        return map;
    }

    private Object parseJsonOrString(String value) {
        try {
            return GSON.fromJson(value, Object.class);
        } catch (Exception e) {
            return value;
        }
    }

    private Map<String, Object> ingredientToMap(Ingredient ingredient) {
        Map<String, Object> map = new HashMap<>();
        map.put("_type", "Ingredient");
        List<String> items = new ArrayList<>();
        for (ItemStack itemStack : ingredient.getItems()) {
            items.add(itemStack.getItem().toString());
        }
        map.put("items", items);
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

    private java.lang.reflect.Field[] getAllFields(Class<?> clazz) {
        List<java.lang.reflect.Field> fields = new ArrayList<>();
        Class<?> currentClass = clazz;
        while (currentClass != null && currentClass != Object.class) {
            fields.addAll(Arrays.asList(currentClass.getDeclaredFields()));
            currentClass = currentClass.getSuperclass();
        }
        return fields.toArray(new java.lang.reflect.Field[0]);
    }

}
