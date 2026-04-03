package com.example.examplemod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;

import java.util.*;
import java.util.Optional;

/**
 * This class is designed to scrape Recipe objects and their properties into JSON
 *
 */
public class RecipeScraper {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public Map<String, Object> objectToMap(Object obj) {
        String objType = obj.getClass().getSimpleName();

        // If the object needs special handling, do so
        if (obj instanceof ItemStack) {
            // Avoid infinite recursion
            return itemStackToMap((ItemStack) obj);
        }
        if (obj instanceof Ingredient) {
            return ingredientToMap((Ingredient) obj);
        }

//        if (obj instanceof ShapedRecipe) {
//            return shapedRecipeToMap((ShapedRecipe) obj);
//        }

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
                    // By default, just toString it, we can see what it is in the output JSON
                    map.put(fieldName, String.valueOf(value));
                }
            }
        } catch (Exception e) {
            map.put("_error", e.getMessage());
        }

        return map;
    }

    /**
     * Converts a recipe to a map with slots extracted from the recipe category.
     * Combines the recipe data from objectToMap with slot positions from SlotExtractor.
     *
     * @param recipe The recipe object
     * @param category The recipe category (used for slot extraction)
     * @return Map containing recipe data with slots
     */
    public Map<String, Object> recipeToMap(Object recipe, IRecipeCategory category) {
        Map<String, Object> recipeMap = objectToMap(recipe);

        SlotExtractor slotExtractor = new SlotExtractor();
        Optional<List<CapturedSlot>> slots = slotExtractor.captureSlotsFromRecipe(category, recipe);

        if (slots.isPresent()) {
            List<Map<String, Object>> slotMaps = new ArrayList<>();
            for (CapturedSlot slot : slots.get()) {
                slotMaps.add(slot.toMap());
            }
            recipeMap.put("slots", slotMaps);
        }

        return recipeMap;
    }

    /**
     * Given a ShapedRecipe, extract into a Map
     * We should replace this with the generic extractor at some point
     * @param recipe The ShapedRecipe
     * @return
     */
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

    /**
     * Takes an ItemStack and converts it into JSON (Map)
     * Represents a stack of items, but in JEI could be alternative items included.
     * @param itemStack
     * @return
     */
    private Map<String, Object> itemStackToMap(ItemStack itemStack) {
        // ItemStack requires special scraping, since it has other ItemStacks as properties of itself
        // Infinite Recursion :(
        Map<String, Object> map = new HashMap<>();
        map.put("_type", "ItemStack");
        map.put("name", itemStack.getHoverName().getString());
        map.put("count", itemStack.getCount());
        map.put("item", itemStack.getItem().toString());

        // NBT is typically fetched as a Tag, but we extract the whole thing to take a look
        map.put("tag", itemStack.getTag() != null ? parseJsonOrString(itemStack.getTag().toString()) : null);
        map.put("capNBT", parseJsonOrString(itemStack.serializeNBT().toString()));

        // Durability is computed as damage and maxDamage
        map.put("damage", itemStack.getDamageValue());
        map.put("maxDamage", itemStack.getMaxDamage());
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

        // getItems is allowed, so use it to grab the items
        var items = new ArrayList<>();
        for (ItemStack itemStack : ingredient.getItems()) {
            items.add(itemStackToMap(itemStack));
            //items.add(itemStack.getItem().toString());
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

    /**
     * Given a class, get a list of ALL fields, including inherited ones.
     * @param clazz Class
     * @return Array of fields
     */
    private java.lang.reflect.Field[] getAllFields(Class<?> clazz) {
        List<java.lang.reflect.Field> fields = new ArrayList<>();
        Class<?> currentClass = clazz;
        while (currentClass != null && currentClass != Object.class) {
            fields.addAll(Arrays.asList(currentClass.getDeclaredFields()));
            currentClass = currentClass.getSuperclass();
        }
        return fields.toArray(new java.lang.reflect.Field[0]);
    }

    public String getRecipeId(Object recipe) {
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

    private final Map<String, List<String>> ingredientsMap = new HashMap<>();
    private final Map<String, String> ingredientLookup = new HashMap<>();  // maps sorted item names to ingredient ID
    private int ingredientCounter = 1;
}
