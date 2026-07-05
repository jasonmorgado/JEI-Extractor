package com.example.examplemod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.Optional;

/**
 * This class is designed to scrape Recipe objects and their properties into JSON
 *
 */
public class RecipeScraper {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson GSON_COMPACT = new Gson();

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
     * Takes an ItemStack and converts it into JSON (Map)
     * Represents a stack of items, but in JEI could be alternative items included.
     * @param itemStack
     * @return
     */
    public Map<String, Object> itemStackToMap(ItemStack itemStack) {
        // ItemStack requires special scraping, since it has other ItemStacks as properties of itself
        // Infinite Recursion :(
        Map<String, Object> map = new HashMap<>();
        map.put("_type", "ItemStack");
        map.put("name", itemStack.getHoverName().getString());
        map.put("count", itemStack.getCount());
        map.put("item", itemStack.getItem().toString());


        // NBT is typically fetched as a Tag, but we extract the whole thing to take a look
        CompoundTag rawTag = itemStack.getTag();
        Object tag = rawTag != null ? parseJsonOrString(rawTag.toString()) : null;
        map.put("tag", tag);
        Object capNBT = parseJsonOrString(itemStack.serializeNBT().toString());
        map.put("capNBT", capNBT);
        if (capNBT instanceof Map) {
            Object idObj = ((Map<String, Object>) capNBT).get("id");
            if (idObj != null) {
                String resourceLocation = idObj.toString();
                String normalizedResourceLocation = resourceLocation.replace(":", "__");
                String uid = normalizedResourceLocation;
                if (rawTag != null) {
                    try {
                        String tagHash = hashNBT(rawTag);
                        uid = normalizedResourceLocation + "__" + tagHash;
                    } catch (Exception e) {
                        System.out.println("Failed to hash NBT tag: " + e.getMessage());
                    }
                }
                map.put("resourceLocation", resourceLocation);
                map.put("uid", uid);
            }
        } else {
            System.out.println("capNBT is not a Map, got: " + (capNBT != null ? capNBT.getClass().getSimpleName() : "null"));
        }

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

    public static String hashNBT(CompoundTag tag) throws Exception {
        String snbt = tag.toString(); // e.g. {Potion:"minecraft:strength"}

        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] hash = md.digest(snbt.getBytes(StandardCharsets.UTF_8));

        // Convert to hex string
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private final Map<String, List<String>> ingredientsMap = new HashMap<>();
    private final Map<String, String> ingredientLookup = new HashMap<>();  // maps sorted item names to ingredient ID
    private int ingredientCounter = 1;
}
