package com.example.examplemod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.JsonSyntaxException;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/// IndexBuilder processes recipe files from ../out/recipe_types and reorganizes them
/// into indexed formats for efficient lookup.
///
/// Input: Files named {crafting_type}.json containing recipe objects with slots.
/// Each recipe contains slots with items that have capNBT.id field.
///
/// Output: recipe_type_index.json mapping Item ID + Role -> RecipeTypes
/// and RecipeType + Item + Role -> RecipeID List
///
/// Goal:
/// Given Item ID and Role (Input/Output, Left/Right click) get Types needed to load
/// For each RecipeType + Item + Role, get Recipe list.
public class IndexBuilder {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(Object.class, new JsonSerializer<Object>() {
                @Override
                public JsonElement serialize(Object src, Type typeOfSrc, JsonSerializationContext context) {
                    try {
                        return context.serialize(src);
                    } catch (Exception e) {
                        return new JsonPrimitive(src.toString());
                    }
                }
            })
            .create();

    /**
     * Build both indexes from recipe files in the source directory.
     *
     * @param recipeTypesDir Directory containing {crafting_type}.json files
     * @param outDir Directory to write indexed files to
     * @throws IOException if file I/O fails
     */
    public void buildIndexes(Path recipeTypesDir, Path outDir) throws IOException {
        if (!Files.exists(recipeTypesDir)) {
            LOGGER.warn("Recipe types directory does not exist: {}", recipeTypesDir);
            return;
        }
        buildRecipeTypeIndex(recipeTypesDir, outDir);
        buildRecipeIndex(recipeTypesDir, outDir);
    }

    /**
     * Read and parse a Recipe file into a JsonArray.
     *
     * @param recipeFile The path to the recipe file
     * @return Optional containing the JsonArray, or empty if parsing fails
     */
    private Optional<JsonArray> readRecipeFile(Path recipeFile) {
        try {
            String fileContent = Files.readString(recipeFile);
            JsonArray recipes = GSON.fromJson(fileContent, JsonArray.class);
            return Optional.ofNullable(recipes);
        } catch (IOException e) {
            LOGGER.error("Failed to read recipe file {}: {}", recipeFile, e.getMessage());
            return Optional.empty();
        } catch (JsonSyntaxException e) {
            LOGGER.error("Failed to parse JSON in recipe file {}: {}", recipeFile, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Generates recipe_type_index index from recipe files in the source directory.
     *
     * @param recipeTypesDir Directory containing {crafting_type}.json files
     * @param outDir Directory to write indexed files to
     * @throws IOException if file I/O fails
     */
    public void buildRecipeTypeIndex(Path recipeTypesDir, Path outDir) throws IOException {
        var itemToRecipeTypes = new LinkedHashMap<String, Map<String, Set<String>>>();

        // Process each {crafting_type}.json file in the recipe_types directory
        List<Path> recipeFiles = Files.list(recipeTypesDir)
                .filter(p -> p.toString().endsWith(".json"))
                .sorted()
                .toList();

        for (Path recipeFile : recipeFiles) {
            String filename = recipeFile.getFileName().toString();
            // Convert filename to crafting type: "minecraft_crafting.json" -> "minecraft:crafting"
            String craftingType = convertFilenameToCraftingType(filename);

            var recipes = readRecipeFile(recipeFile);
            if (recipes.isEmpty()) continue;
            processRecipesForCraftingType(recipes.get(), craftingType, itemToRecipeTypes);
        }

        // Ensure output directory exists
        Files.createDirectories(outDir);

        // Convert Sets to sorted Lists and write the indexed file
        var sortedIndex = convertSetsToSortedLists(itemToRecipeTypes);
        writeJsonFile(outDir, "recipe_type_index.json", sortedIndex);
    }

    /**
     * Process all recipes for a specific crafting type and update the item index.
     * Helper Function specific to buildRecipeTypeIndex
     *
     * @param recipes JsonArray of recipe objects
     * @param craftingType The crafting type (e.g., "minecraft:crafting")
     * @param itemToRecipeTypes The accumulating index map
     */
    private void processRecipesForCraftingType(
        JsonArray recipes, String craftingType, Map<String, Map<String, Set<String>>> itemToRecipeTypes
    ) {
        for (JsonElement recipeElement : recipes) {
            if (recipeElement.isJsonObject()) {
                JsonObject recipe = recipeElement.getAsJsonObject();
                processRecipeSlots(recipe, craftingType, itemToRecipeTypes);
            }
        }
    }

    /**
     * Process all slots in a recipe and extract item IDs with their roles.
     *
     * @param recipe The recipe object
     * @param craftingType The crafting type
     * @param itemToRecipeTypes The accumulating index map
     */
    private void processRecipeSlots(
        JsonObject recipe, String craftingType, Map<String, Map<String, Set<String>>> itemToRecipeTypes
    ) {
        JsonElement slotsElement = recipe.get("slots");
        if (slotsElement == null || !slotsElement.isJsonArray()) {
            return;
        }

        JsonArray slots = slotsElement.getAsJsonArray();
        for (JsonElement slotElement : slots) {
            if (slotElement.isJsonObject()) {
                JsonObject slot = slotElement.getAsJsonObject();
                processSlotItems(slot, craftingType, itemToRecipeTypes);
            }
        }
    }

    /**
     * Process all items in a slot and add them to the index with their role.
     *
     * @param slot The slot object
     * @param craftingType The crafting type
     * @param itemToRecipeTypes The accumulating index map
     */
    private void processSlotItems(JsonObject slot, String craftingType,
                                  Map<String, Map<String, Set<String>>> itemToRecipeTypes) {
        String role = extractRoleFromJson(slot);
        if (role == null) {
            return;
        }

        List<String> itemIds = extractItemIdsFromSlotJson(slot);
        for (String itemId : itemIds) {
            // Add to index: item -> role -> {crafting types}
            itemToRecipeTypes
                    .computeIfAbsent(itemId, k -> new LinkedHashMap<>())
                    .computeIfAbsent(role, k -> new HashSet<>())
                    .add(craftingType);
        }
    }

    /**
     * Build the recipe_index index from recipe files.
     *
     * @param recipeTypesDir Directory containing {crafting_type}.json files
     * @param outDir Directory to write indexed files to
     * @throws IOException if file I/O fails
     */
    public void buildRecipeIndex(Path recipeTypesDir, Path outDir) throws IOException {
        var recipeTypeToItemToRecipeId = new LinkedHashMap<String, Map<String, Map<String, Set<Integer>>>>();

        // Process each {crafting_type}.json file in the recipe_types directory
        List<Path> recipeFiles = Files.list(recipeTypesDir)
                .filter(p -> p.toString().endsWith(".json"))
                .sorted()
                .toList();

        for (Path recipeFile : recipeFiles) {
            String filename = recipeFile.getFileName().toString();
            // Convert filename to crafting type: "minecraft_crafting.json" -> "minecraft:crafting"
            String craftingType = convertFilenameToCraftingType(filename);

            var recipes = readRecipeFile(recipeFile);
            if (recipes.isEmpty()) {
                continue;
            }
            processRecipesForRecipeTypeIndex(recipes.get(), craftingType, recipeTypeToItemToRecipeId);
        }

        // Ensure output directory exists
        Files.createDirectories(outDir);

        // Convert Sets to sorted Lists and write the indexed file
        var sortedIndex = convertNestedSetsToSortedLists(recipeTypeToItemToRecipeId);
        writeJsonFile(outDir, "recipe_index.json", sortedIndex);
    }

    /**
     * Process all recipes for a specific crafting type and build the recipe type to item index.
     *
     * @param recipes JsonArray of recipe objects
     * @param craftingType The crafting type (e.g., "minecraft:crafting")
     * @param recipeTypeToItemToRecipeId The accumulating index map
     */
    private void processRecipesForRecipeTypeIndex(JsonArray recipes, String craftingType,
                                                   Map<String, Map<String, Map<String, Set<Integer>>>> recipeTypeToItemToRecipeId) {
        for (int index = 0; index < recipes.size(); index++) {
            JsonElement recipeElement = recipes.get(index);
            if (recipeElement.isJsonObject()) {
                JsonObject recipe = recipeElement.getAsJsonObject();
                processRecipeSlotsForRecipeTypeIndex(recipe, craftingType, index, recipeTypeToItemToRecipeId);
            }
        }
    }

    /**
     * Process all slots in a recipe and extract item IDs with their roles for recipe type index.
     *
     * @param recipe The recipe object
     * @param craftingType The crafting type
     * @param recipeId The generated recipe ID (index)
     * @param recipeTypeToItemToRecipeId The accumulating index map
     */
    private void processRecipeSlotsForRecipeTypeIndex(JsonObject recipe, String craftingType, int recipeId,
                                                      Map<String, Map<String, Map<String, Set<Integer>>>> recipeTypeToItemToRecipeId) {
        JsonElement slotsElement = recipe.get("slots");
        if (slotsElement == null || !slotsElement.isJsonArray()) {
            return;
        }

        JsonArray slots = slotsElement.getAsJsonArray();
        for (JsonElement slotElement : slots) {
            if (slotElement.isJsonObject()) {
                JsonObject slot = slotElement.getAsJsonObject();
                processSlotItemsForRecipeTypeIndex(slot, craftingType, recipeId, recipeTypeToItemToRecipeId);
            }
        }
    }

    /**
     * Process all items in a slot and add them to the recipe type index with their role.
     *
     * @param slot The slot object
     * @param craftingType The crafting type
     * @param recipeId The generated recipe ID (index)
     * @param recipeTypeToItemToRecipeId The accumulating index map
     */
    private void processSlotItemsForRecipeTypeIndex(JsonObject slot, String craftingType, int recipeId,
                                                    Map<String, Map<String, Map<String, Set<Integer>>>> recipeTypeToItemToRecipeId) {
        String role = extractRoleFromJson(slot);
        if (role == null) {
            return;
        }

        List<String> itemIds = extractItemIdsFromSlotJson(slot);
        for (String itemId : itemIds) {
            // Add to index: recipe_type -> item -> role -> {recipe_ids}
            recipeTypeToItemToRecipeId
                    .computeIfAbsent(craftingType, k -> new LinkedHashMap<>())
                    .computeIfAbsent(itemId, k -> new LinkedHashMap<>())
                    .computeIfAbsent(role, k -> new HashSet<>())
                    .add(recipeId);
        }
    }

    /**
     * Convert nested Sets of recipe IDs to sorted Lists for JSON serialization.
     *
     * @param recipeTypeToItemWithSets Map with Sets of recipe IDs
     * @return Map with sorted Lists of recipe IDs
     */
    private Map<String, Map<String, Map<String, List<Integer>>>>
    convertNestedSetsToSortedLists(
        Map<String, Map<String, Map<String, Set<Integer>>>> recipeTypeToItemWithSets
    ) {
        var result = new LinkedHashMap<String, Map<String, Map<String, List<Integer>>>>();

        for (var typeEntry : recipeTypeToItemWithSets.entrySet()) {
            var itemMap = new LinkedHashMap<String, Map<String, List<Integer>>>();

            for (var itemEntry : typeEntry.getValue().entrySet()) {
                var roleMap = new LinkedHashMap<String, List<Integer>>();

                for (Map.Entry<String, Set<Integer>> roleEntry : itemEntry.getValue().entrySet()) {
                    List<Integer> sortedRecipeIds = roleEntry.getValue().stream()
                            .sorted()
                            .collect(Collectors.toList());
                    roleMap.put(roleEntry.getKey(), sortedRecipeIds);
                }

                if (!roleMap.isEmpty()) {
                    itemMap.put(itemEntry.getKey(), roleMap);
                }
            }

            if (!itemMap.isEmpty()) {
                result.put(typeEntry.getKey(), itemMap);
            }
        }

        return result;
    }

    /**
     * Convert Sets of recipe types to sorted Lists for JSON serialization.
     *
     * @param itemToRecipeTypesWithSets Map with Sets of recipe types
     * @return Map with sorted Lists of recipe types
     */
    private Map<String, Map<String, List<String>>> convertSetsToSortedLists(
            Map<String, Map<String, Set<String>>> itemToRecipeTypesWithSets) {
        var result = new LinkedHashMap<String, Map<String, List<String>>>();

        for (var itemEntry : itemToRecipeTypesWithSets.entrySet()) {
            var roleMap = new LinkedHashMap<String, List<String>>();

            for (var roleEntry : itemEntry.getValue().entrySet()) {
                List<String> sortedRecipeTypes = roleEntry.getValue().stream()
                        .sorted()
                        .collect(Collectors.toList());
                roleMap.put(roleEntry.getKey(), sortedRecipeTypes);
            }

            result.put(itemEntry.getKey(), roleMap);
        }

        return result;
    }

    /**
     * Convert a recipe filename to a crafting type.
     * Example: "minecraft_crafting.json" -> "minecraft:crafting"
     *
     * @param filename The filename without directory
     * @return The crafting type
     */
    private String convertFilenameToCraftingType(String filename) {
        // Remove .json extension
        String withoutExt = filename.endsWith(".json") ? filename.substring(0, filename.length() - 5) : filename;
        // Replace first underscore with colon, keep rest as is
        int firstUnderscore = withoutExt.indexOf('_');
        if (firstUnderscore > 0) {
            return withoutExt.substring(0, firstUnderscore) + ":" + withoutExt.substring(firstUnderscore + 1);
        }
        return withoutExt;
    }

    /**
     * Extract the role from a JSON slot object.
     *
     * @param slot The slot JSON object
     * @return The role string (INPUT/OUTPUT), or null if not found
     */
    private String extractRoleFromJson(JsonObject slot) {
        JsonElement roleElement = slot.get("role");
        if (roleElement != null && roleElement.isJsonPrimitive()) {
            return roleElement.getAsString();
        }
        return null;
    }

    /**
     * Extract all item IDs from a JSON slot object's items array.
     * Item IDs are located in capNBT.id fields within each item.
     *
     * @param slot The slot JSON object
     * @return List of item ID strings
     */
    private List<String> extractItemIdsFromSlotJson(JsonObject slot) {
        var itemIds = new ArrayList<String>();

        JsonElement itemsElement = slot.get("items");
        if (itemsElement == null || !itemsElement.isJsonArray()) {
            return itemIds;
        }

        JsonArray items = itemsElement.getAsJsonArray();
        for (JsonElement itemElement : items) {
            if (!itemElement.isJsonObject()) {
                continue;
            }

            JsonObject item = itemElement.getAsJsonObject();
            JsonElement capNBTElement = item.get("capNBT");
            if (capNBTElement == null || !capNBTElement.isJsonObject()) {
                continue;
            }

            JsonObject capNBT = capNBTElement.getAsJsonObject();
            JsonElement idElement = capNBT.get("id");
            if (idElement == null || !idElement.isJsonPrimitive()) {
                continue;
            }

            String itemId = idElement.getAsString();
            if (!itemId.isEmpty()) {
                itemIds.add(itemId);
            }
        }

        return itemIds;
    }

    /**
     * Write a data structure to a JSON file.
     *
     * @param outDir The output directory
     * @param filename The filename to write to
     * @param data The data to serialize
     * @throws IOException if file I/O fails
     */
    private void writeJsonFile(Path outDir, String filename, Object data) throws IOException {
        Path filePath = outDir.resolve(filename);
        String content = GSON.toJson(data);
        Files.writeString(filePath, content);
        LOGGER.info("Wrote {}", filePath.getFileName());
    }
}
