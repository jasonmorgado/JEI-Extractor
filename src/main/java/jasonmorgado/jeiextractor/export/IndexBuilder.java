package jasonmorgado.jeiextractor.export;

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
/// Additionally adds fallback_uid for slots that have ItemStacks not in items.json. 
/// This can be used for recipe lookups or icon fallbacks.
///
/// Output:
///   - recipe_type_index.json mapping Item ID + Role -> RecipeTypes
///   - recipe_index.json mapping RecipeType + Item + Role -> Recipe Index List
///   - fallback_resource_id_to_uid.json: resourceLocations that needed a fallback
///     mapped to the fallback_uid to use.
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

    // --- Safe Gson traversal helpers ---

    /** Safely unwrap a JsonElement to JsonObject, or empty. */
    private static Optional<JsonObject> tryObject(JsonElement el) {
        return el != null && el.isJsonObject() ? Optional.of(el.getAsJsonObject()) : Optional.empty();
    }

    /** Get a child as JsonArray, or empty. */
    private static Optional<JsonArray> tryArray(JsonObject parent, String key) {
        JsonElement el = parent.get(key);
        return el != null && el.isJsonArray() ? Optional.of(el.getAsJsonArray()) : Optional.empty();
    }

    /** Get a child as a String primitive, or empty. */
    private static Optional<String> tryString(JsonObject parent, String key) {
        JsonElement el = parent.get(key);
        return el != null && el.isJsonPrimitive() ? Optional.of(el.getAsString()) : Optional.empty();
    }

    // UIDs present as keys in items.json — used to know which items have a direct entry
    private Set<String> validUids;
    // resourceLocation → list of uids lookup — used internally to find fallback uids
    private Map<String, List<String>> resourceIdToUids;
    // resourceLocations that actually triggered a fallback → the fallback_uid used
    private final Map<String, String> fallbackResourceToUid = new LinkedHashMap<>();

    /**
     * Build indexes (and fallback file) from recipe files.
     *
     * @param validUids         Set of uids that exist as keys in items.json (used to determine fallback need)
     * @param resourceIdToUids  resourceLocation → list of uids map (used internally for fallback lookup)
     * @param recipeTypesDir    Directory containing {crafting_type}.json files
     * @param outDir            Directory to write indexed files to
     * @throws IOException if file I/O fails
     */
    public void buildIndexes(Set<String> validUids, Map<String, List<String>> resourceIdToUids, Path recipeTypesDir, Path outDir) throws IOException {
        this.validUids = validUids;
        this.resourceIdToUids = resourceIdToUids;
        this.fallbackResourceToUid.clear();

        Files.createDirectories(outDir);

        // Enrich recipe files with fallback_uids before building indexes
        if (Files.exists(recipeTypesDir)) {
            enrichRecipeFilesWithFallbackUid(recipeTypesDir);
        }

        // Build recipe indexes from recipe type files
        if (Files.exists(recipeTypesDir)) {
            buildRecipeTypeIndex(recipeTypesDir, outDir);
            buildRecipeIndex(recipeTypesDir, outDir);
        } else {
            LOGGER.warn("Recipe types directory does not exist: {}", recipeTypesDir);
        }

        // Write fallback-only resourceId→uid file
        writeFallbackResourceIdToUidFile(outDir);
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
     * If a slot has an ItemStack not in items.json, add fallback_uid to an item of the same resourceLocation that does.
     * Can be used for recipe lookups and icon lookups.
     * 
     * @param item The slot item JSON object to enrich (modified in place)
     */
    public void addFallbackUidToSlotItem(JsonObject item) {
        if (validUids == null || resourceIdToUids == null) {
            return;
        }

        var optionalUid = tryString(item, "uid");
        var optionalResourceLocation = tryString(item, "resourceLocation");
        if (optionalUid.isEmpty() || optionalResourceLocation.isEmpty()) {
            return;
        }

        String uid = optionalUid.get();
        String resourceLocation = optionalResourceLocation.get();

        // If this uid is already in items.json, no fallback needed
        if (validUids.contains(uid)) {
            return;
        }

        List<String> uids = resourceIdToUids.get(resourceLocation);
        if (uids != null && !uids.isEmpty()) {
            String fallbackUid = uids.get(0);
            item.addProperty("fallback_uid", fallbackUid);
            // Record this fallback — the first occurrence sets the fallback_uid for this resourceLocation
            fallbackResourceToUid.putIfAbsent(resourceLocation, fallbackUid);
            LOGGER.debug("Added fallback_uid '{}' for item '{}' (uid '{}' not in items.json)",
                    fallbackUid, resourceLocation, uid);
        } else {
            LOGGER.debug("No fallback uid found for resourceLocation '{}' (uid '{}' not in items.json)",
                    resourceLocation, uid);
        }
    }

    /**
     * Go through all recipes files and add fallback_uid to slots where needed.
     *
     * @param recipeTypesDir Directory containing {crafting_type}.json files
     * @throws IOException if file I/O fails
     */
    public void enrichRecipeFilesWithFallbackUid(Path recipeTypesDir) throws IOException {
        List<Path> recipeFiles = Files.list(recipeTypesDir)
                .filter(p -> p.toString().endsWith(".json"))
                .sorted()
                .toList();

        for (Path recipeFile : recipeFiles) {
            var recipes = readRecipeFile(recipeFile);
            if (recipes.isEmpty()) continue;

            boolean modified = false;
            for (JsonElement recipeElement : recipes.get()) {
                var recipe = tryObject(recipeElement);
                if (recipe.isEmpty()) continue;
                var slots = tryArray(recipe.get(), "slots");
                if (slots.isEmpty()) continue;

                for (JsonElement slotElement : slots.get()) {
                    var slot = tryObject(slotElement);
                    if (slot.isEmpty()) continue;
                    var items = tryArray(slot.get(), "items");
                    if (items.isEmpty()) continue;

                    for (JsonElement itemElement : items.get()) {
                        var item = tryObject(itemElement);
                        if (item.isEmpty()) continue;
                        if (item.get().has("uid")) {
                            addFallbackUidToSlotItem(item.get());
                            if (item.get().has("fallback_uid")) {
                                modified = true;
                            }
                        }
                    }
                }
            }

            if (modified) {
                String content = GSON.toJson(recipes.get());
                Files.writeString(recipeFile, content);
                LOGGER.info("Enriched {} with fallback_uids", recipeFile.getFileName());
            }
        }
    }

    /**
     * Write fallback_resource_id_to_uid.json containing only resourceLocations
     * that actually triggered a fallback.
     * Maps each resourceLocation to the fallback_uid to use.
     *
     * @param outDir Output directory
     * @throws IOException if file I/O fails
     */
    public void writeFallbackResourceIdToUidFile(Path outDir) throws IOException {
        if (fallbackResourceToUid.isEmpty()) {
            return;
        }

        Path mappingFile = outDir.resolve("fallback_resource_id_to_uid.json");
        String content = GSON.toJson(fallbackResourceToUid);
        Files.writeString(mappingFile, content);
        LOGGER.info("Wrote fallback-only resourceId→uid mapping to {} ({} entries)",
                mappingFile.getFileName(), fallbackResourceToUid.size());
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
        // Skip tag recipes — they're enormous and inflate indexes
        List<Path> recipeFiles = Files.list(recipeTypesDir)
                .filter(p -> p.toString().endsWith(".json"))
                .filter(p -> !p.getFileName().toString().contains("tag_recipes"))
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
            tryObject(recipeElement).ifPresent(
                    recipe -> processRecipeSlots(recipe, craftingType, itemToRecipeTypes));
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
        var slots = tryArray(recipe, "slots");
        if (slots.isEmpty()) return;

        for (JsonElement slotElement : slots.get()) {
            tryObject(slotElement).ifPresent(
                    slot -> processSlotItems(slot, craftingType, itemToRecipeTypes));
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
        // Skip tag recipes — they're enormous and inflate indexes
        List<Path> recipeFiles = Files.list(recipeTypesDir)
                .filter(p -> p.toString().endsWith(".json"))
                .filter(p -> !p.getFileName().toString().contains("tag_recipes"))
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
            int recipeIndex = index;  // effectively final for lambda
            tryObject(recipes.get(index)).ifPresent(
                    recipe -> processRecipeSlotsForRecipeTypeIndex(recipe, craftingType, recipeIndex, recipeTypeToItemToRecipeId));
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
        var slots = tryArray(recipe, "slots");
        if (slots.isEmpty()) return;

        for (JsonElement slotElement : slots.get()) {
            tryObject(slotElement).ifPresent(
                    slot -> processSlotItemsForRecipeTypeIndex(slot, craftingType, recipeId, recipeTypeToItemToRecipeId));
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
        // Replace first underscore with colon, rest keep as is
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
        return tryString(slot, "role").orElse(null);
    }

    /**
     * Extract all item UIDs from a JSON slot object's items array.
     * Item UIDs are located in the uid field within each item.
     *
     * @param slot The slot JSON object
     * @return List of item UID strings
     */
    private List<String> extractItemIdsFromSlotJson(JsonObject slot) {
        var itemIds = new ArrayList<String>();
        var items = tryArray(slot, "items");
        if (items.isEmpty()) return itemIds;

        for (JsonElement itemElement : items.get()) {
            tryObject(itemElement)
                    .flatMap(item -> tryString(item, "uid"))
                    .filter(id -> !id.isEmpty())
                    .ifPresent(itemIds::add);
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
