package com.example.examplemod;

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
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

@JeiPlugin
public class JEIPlugin implements IModPlugin {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final Map<String, List<String>> ingredientsMap = new HashMap<>();
    private final Map<String, String> ingredientLookup = new HashMap<>();  // maps sorted item names to ingredient ID
    private int ingredientCounter = 1;

    @Override
    public @NotNull ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "jei_plugin");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        // Runs when the world is loaded and JEI's recipe manager is available
        IRecipeManager recipeManager = jeiRuntime.getRecipeManager();

        // Need to get info from Recipe Manager
        // IRecipeManager provides a IRecipeCategories, which each have their own IRecipeType
        // Each IRecipeType can fetch all Recipes of that type
        // RecipeCategory.setRecipe uses the builder to ingest recipes into the internal recipe manager, probably.



        Collection<RecipeType<?>> recipeTypes = recipeManager.createRecipeCategoryLookup()
                .get()
                .map(IRecipeCategory::getRecipeType)
                .collect(Collectors.toList());

        Path outDir = Paths.get("../out");
        try {
            Files.createDirectories(outDir);

            // Extract slots from Categories + Recipes
            Map<String, Map<String, List<CapturedSlot>>> slotsByTypeAndRecipe = extractSlotsFromAllCategories(recipeManager);

            // Convert slots to JSON-serializable format
            Map<String, Map<String, List<Map<String, Object>>>> slotsJsonMap = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, List<CapturedSlot>>> typeEntry : slotsByTypeAndRecipe.entrySet()) {
                Map<String, List<Map<String, Object>>> recipesMap = new LinkedHashMap<>();
                for (Map.Entry<String, List<CapturedSlot>> recipeEntry : typeEntry.getValue().entrySet()) {
                    List<Map<String, Object>> slotMaps = recipeEntry.getValue().stream()
                            .map(CapturedSlot::toMap)
                            .collect(Collectors.toList());
                    recipesMap.put(recipeEntry.getKey(), slotMaps);
                }
                slotsJsonMap.put(typeEntry.getKey(), recipesMap);
            }

            // Write slots to JSON file
            Path slotsFile = outDir.resolve("recipe_slots.json");
            String slotsContent = GSON.toJson(slotsJsonMap);
            Files.writeString(slotsFile, slotsContent);
            LOGGER.info("Wrote slots for {} recipe types to {}", slotsJsonMap.size(), slotsFile.getFileName());
            // Write list of recipe types to a file
//            writeRecipeTypesFile(outDir, recipeTypes);
//
            // Write per-type recipe files
//            Map<String, Map<String, Object>> onePerType = buildOnePerTypeMap(recipeManager, recipeTypes);
//            for (RecipeType<?> type : recipeTypes) {
//                writeRecipesForType(recipeManager, outDir, type);
//            }

            // Write one_per_type.json
//            Path onePerTypeFile = outDir.resolve("one_per_type.json");
//            String onePerTypeContent = GSON.toJson(onePerType);
//            Files.writeString(onePerTypeFile, onePerTypeContent);
//            LOGGER.info("Wrote one recipe per type to {}", onePerTypeFile.getFileName());

            // Write ingredients file
//            writeIngredientsFile(outDir);
        } catch (IOException e) {
            LOGGER.error("Failed to write recipe types: {}", e.getMessage());
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Fetches one recipe per RecipeType, nondeterministically (It's random)
     * Good for debugging through Recipe objects to see what's inside.
     * @param recipeManager - JEI Recipe Manager
     * @param recipeTypes - List of RecipeTypes
     * @return A Map of item uids -> JSON containing scraped Recipe data.
     */
    private Map<String, Map<String, Object>> buildOnePerTypeMap(IRecipeManager recipeManager, Collection<RecipeType<?>> recipeTypes) {
        Map<String, Map<String, Object>> onePerType = new LinkedHashMap<>();
        for (RecipeType<?> type : recipeTypes) {
            IRecipeLookup<?> recipeLookup = recipeManager.createRecipeLookup(type);
            Optional<?> firstRecipe = recipeLookup.get().findFirst();
            if (firstRecipe.isEmpty()){
                continue;
            }
            Recipe<?> recipe = (Recipe<?>) firstRecipe.get();
            Map<String, Object> recipeMap = objectToMap(recipe);
            onePerType.put(type.getUid().toString(), recipeMap);
        }
        return onePerType;
    }

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

    private void writeRecipeTypesFile(Path outDir, Collection<RecipeType<?>> recipeTypes) throws IOException {
        Path typesFile = outDir.resolve("recipe_types.txt");
        Files.writeString(typesFile, recipeTypes.stream()
                .map(RecipeType::toString)
                .collect(Collectors.joining("\n")));
        LOGGER.info("JEI Recipe Types written to {}", typesFile.toAbsolutePath());
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

    private Map<String, Map<String, List<CapturedSlot>>> extractSlotsFromAllCategories(IRecipeManager recipeManager) {
        Map<String, Map<String, List<CapturedSlot>>> slotsMap = new LinkedHashMap<>();

        List<IRecipeCategory<?>> categories = recipeManager.createRecipeCategoryLookup()
                .get()
                .toList();

        for (IRecipeCategory<?> category : categories) {
            RecipeType<?> recipeType = category.getRecipeType();
            IRecipeLookup<?> recipeLookup = recipeManager.createRecipeLookup(recipeType);

            recipeLookup.get().forEach(recipe -> {
                captureSlotsFromRecipe(category, recipe, slotsMap);
            });
        }

        return slotsMap;
    }

    @SuppressWarnings("unchecked")
    private void captureSlotsFromRecipe(IRecipeCategory<?> category, Object recipe, Map<String, Map<String, List<CapturedSlot>>> slotsMap) {
        CapturingLayoutBuilder layoutBuilder = new CapturingLayoutBuilder();
        try {
            ((IRecipeCategory<Object>) category).setRecipe(layoutBuilder, recipe, null);
            RecipeType<?> recipeType = category.getRecipeType();
            String typeId = recipeType.getUid().toString();
            String recipeId = getRecipeId(recipe);
            List<CapturedSlot> slots = layoutBuilder.getSlots();

            slotsMap.computeIfAbsent(typeId, k -> new LinkedHashMap<>()).put(recipeId, slots);
        } catch (Exception e) {
            LOGGER.warn("Failed to capture slots for recipe: {}", e.getMessage());
        }
    }

    private String getRecipeId(Object recipe) {
        if (recipe instanceof Recipe) {
            return ((Recipe<?>) recipe).getId().toString();
        }
        try {
            Map<String, Object> recipeMap = objectToMap(recipe);
            Object id = recipeMap.get("id");
            return id != null ? id.toString() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

}
