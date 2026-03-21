package com.example.examplemod;

import com.mojang.logging.LogUtils;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.category.IRecipeCategory;
import java.util.stream.Collectors;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;
import java.util.HashMap;
import java.util.Map;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

@JeiPlugin
public class JEIPlugin implements IModPlugin {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public ResourceLocation getPluginUid() {
        return new ResourceLocation(ExampleMod.MODID, "jei_plugin");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
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
        } catch (IOException e) {
            LOGGER.error("Failed to write recipe types: {}", e.getMessage());
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private <R> void writeRecipesForType(IRecipeManager recipeManager, Path outDir, RecipeType<R> type) {
        try {
            String fileName = type.getUid().getPath() + ".json";
            Path file = outDir.resolve(fileName);

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

    private Map<String, Object> recipeToMap(Object recipe) {
        Map<String, Object> map = new HashMap<>();
        String recipeType = recipe.getClass().getSimpleName();
        map.put("_type", recipeType);

        try {
            // Special handling for ShapedRecipe
            if (recipeType.equals("ShapedRecipe")) {
                addShapedRecipeInfo(map, recipe);
            }

            java.lang.reflect.Field[] fields = recipe.getClass().getDeclaredFields();
            for (java.lang.reflect.Field field : fields) {
                field.setAccessible(true);
                Object value = field.get(recipe);

                // Skip large collections and shape data we've already handled
                if (field.getName().equals("pattern") || field.getName().equals("key")) {
                    continue;
                }

                // Special handling for recipeItems - extract ingredient names
                if (field.getName().equals("recipeItems") && value != null) {
                    List<String> ingredientNames = new java.util.ArrayList<>();
                    if (value instanceof Collection) {
                        for (Object item : (Collection<?>) value) {
                            ingredientNames.add(getIngredientName(item));
                        }
                    } else if (value.getClass().isArray()) {
                        for (Object item : (Object[]) value) {
                            ingredientNames.add(getIngredientName(item));
                        }
                    }
                    map.put(field.getName(), ingredientNames);
                } else {
                    map.put(field.getName(), sanitizeValue(value, new java.util.HashSet<>()));
                }
            }
        } catch (Exception e) {
            map.put("_error", e.getMessage());
        }

        return map;
    }

    private void addShapedRecipeInfo(Map<String, Object> map, Object recipe) {
        try {
            java.lang.reflect.Field patternField = recipe.getClass().getDeclaredField("pattern");
            patternField.setAccessible(true);
            String[] pattern = (String[]) patternField.get(recipe);

            java.lang.reflect.Field keyField = recipe.getClass().getDeclaredField("key");
            keyField.setAccessible(true);
            java.util.Map<String, Object> key = (java.util.Map<String, Object>) keyField.get(recipe);

            List<String> shapeList = new java.util.ArrayList<>();
            for (String line : pattern) {
                shapeList.add(line);
            }
            map.put("shape", shapeList);

            // Convert key to ingredient names
            if (key != null) {
                Map<String, Object> ingredientMap = new HashMap<>();
                for (java.util.Map.Entry<String, Object> entry : key.entrySet()) {
                    Object ingredient = entry.getValue();
                    ingredientMap.put(entry.getKey(), getIngredientName(ingredient));
                }
                map.put("key", ingredientMap);
            }
        } catch (Exception e) {
            // Shape info not available
        }
    }

    private String getIngredientName(Object ingredient) {
        // Try all possible ways to get ingredient information
        String result = tryGetIngredientFromValues(ingredient);
        if (result != null) return result;

        result = tryGetIngredientFromTags(ingredient);
        if (result != null) return result;

        result = tryGetIngredientFromItems(ingredient);
        if (result != null) return result;

        result = tryGetIngredientFromFields(ingredient);
        if (result != null) return result;

        return ingredient.toString();
    }

    private String tryGetIngredientFromValues(Object ingredient) {
        try {
            java.lang.reflect.Method getValuesMethod = ingredient.getClass().getMethod("getValues");
            Object[] values = (Object[]) getValuesMethod.invoke(ingredient);
            if (values != null && values.length > 0) {
                Object value = values[0];
                return extractFromValue(value);
            }
        } catch (Exception e) {
            // Continue
        }
        return null;
    }

    private String extractFromValue(Object value) {
        String valueClassName = value.getClass().getSimpleName();

        if (valueClassName.contains("TagValue")) {
            // Try various field names for tag
            for (String fieldName : new String[]{"tag", "holder", "reference"}) {
                try {
                    java.lang.reflect.Field field = value.getClass().getDeclaredField(fieldName);
                    field.setAccessible(true);
                    Object tag = field.get(value);
                    if (tag != null) {
                        return tag.toString();
                    }
                } catch (Exception e) {
                    // Try next field
                }
            }
        } else if (valueClassName.contains("ItemValue")) {
            try {
                java.lang.reflect.Field itemField = value.getClass().getDeclaredField("item");
                itemField.setAccessible(true);
                Object item = itemField.get(value);
                if (item != null) {
                    return item.toString();
                }
            } catch (Exception e) {
                // Continue
            }
        }

        return null;
    }

    private String tryGetIngredientFromTags(Object ingredient) {
        try {
            java.lang.reflect.Field tagsField = ingredient.getClass().getDeclaredField("tags");
            tagsField.setAccessible(true);
            Object[] tags = (Object[]) tagsField.get(ingredient);
            if (tags != null && tags.length > 0) {
                return tags[0].toString();
            }
        } catch (Exception e) {
            // Continue
        }
        return null;
    }

    private String tryGetIngredientFromItems(Object ingredient) {
        try {
            java.lang.reflect.Method getItemsMethod = ingredient.getClass().getMethod("getItems");
            Object[] items = (Object[]) getItemsMethod.invoke(ingredient);
            if (items != null && items.length > 0) {
                return items[0].toString();
            }
        } catch (Exception e) {
            // Continue
        }
        return null;
    }

    private String tryGetIngredientFromFields(Object ingredient) {
        try {
            for (java.lang.reflect.Field field : ingredient.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(ingredient);
                if (value instanceof java.util.Collection) {
                    java.util.Collection<?> col = (java.util.Collection<?>) value;
                    if (!col.isEmpty()) {
                        Object first = col.iterator().next();
                        String str = first.toString();
                        // Return if it looks like a tag or item ID
                        if (str.contains(":") || str.contains("#")) {
                            return str;
                        }
                    }
                } else if (value != null && field.getType().isArray()) {
                    Object[] arr = (Object[]) value;
                    if (arr.length > 0) {
                        String str = arr[0].toString();
                        if (str.contains(":") || str.contains("#")) {
                            return str;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Continue
        }
        return null;
    }

    private Object sanitizeValue(Object value, java.util.Set<Integer> visited) {
        if (value == null) {
            return null;
        }

        int id = System.identityHashCode(value);
        if (visited.contains(id)) {
            return "[circular reference: " + value.getClass().getSimpleName() + "]";
        }

        // Handle arrays
        if (value.getClass().isArray()) {
            Object[] arr = (Object[]) value;
            List<Object> list = new java.util.ArrayList<>();
            for (Object item : arr) {
                list.add(sanitizeValue(item, visited));
            }
            return list;
        }

        // Handle Collections
        if (value instanceof Collection) {
            return ((Collection<?>) value).stream()
                    .limit(100) // Limit to prevent huge arrays
                    .map(item -> sanitizeValue(item, visited))
                    .collect(Collectors.toList());
        }

        // Handle Ingredient specifically - just return the name
        if (value.getClass().getSimpleName().equals("Ingredient")) {
            return getIngredientName(value);
        }

        // Handle ItemStack
        if (value.getClass().getSimpleName().equals("ItemStack")) {
            return itemStackToString(value);
        }

        // Handle basic types
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }

        // For other objects, try to extract toString or return class name
        return value.toString();
    }

private String itemStackToString(Object itemStack) {
        try {
            java.lang.reflect.Method getCountMethod = itemStack.getClass().getMethod("getCount");
            java.lang.reflect.Method getItemMethod = itemStack.getClass().getMethod("getItem");
            int count = (int) getCountMethod.invoke(itemStack);
            Object item = getItemMethod.invoke(itemStack);
            return count + " " + item;
        } catch (Exception e) {
            return itemStack.toString();
        }
    }
}
