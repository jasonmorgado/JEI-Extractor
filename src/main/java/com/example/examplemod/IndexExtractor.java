package com.example.examplemod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.mojang.logging.LogUtils;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.common.recipes.collect.IngredientToRecipesMap;
import mezz.jei.common.recipes.collect.RecipeIngredientTable;
import mezz.jei.common.recipes.collect.RecipeMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class IndexExtractor {
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
    private static final String ITEMS_FILE = "items.json";
    private static final String RECIPE_TYPE_TO_ITEM = "recipe_type_to_item_to_recipe_id";
    private static final String ITEM_TO_RECIPE_TYPES = "item_to_recipe_types";
    private static final String RECIPES = "recipes";

    /**
     * Writes items.json
     * Which pulls ingredient list from JEI, and populates it with id, name, mod.
     * @param jeiRuntime - JEI Runtime
     * @param outDir - Folder to write files to
     */
    public void writeItemsFile(IJeiRuntime jeiRuntime, Path outDir) throws IOException {
        IIngredientManager ingredientManager = jeiRuntime.getIngredientManager();
        Collection<ItemStack> items = ingredientManager.getAllIngredients(VanillaTypes.ITEM_STACK);

        List<Map<String, Object>> itemsList = new ArrayList<>();
        RecipeScraper recipeScraper = new RecipeScraper();

        // Ingredient Type could be ITEM_STACK, FLUID_STACK. Mekanism adds more like GasStack, InfusionStack
        for (ItemStack itemStack : items){
            // Get full itemStack data using RecipeScraper
            Map<String, Object> itemStackData = recipeScraper.itemStackToMap(itemStack);

            // Get mod info
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(itemStack.getItem());
            String modId = id.getNamespace();
            ModContainer mod = ModList.get().getModContainerById(modId).orElse(null);
            String modName = (mod != null)
                    ? mod.getModInfo().getDisplayName()
                    : modId;

            // Build result map with only desired fields
            Map<String, Object> itemMap = new LinkedHashMap<>();
            itemMap.put("resourceLocation", itemStackData.get("resourceLocation"));
            itemMap.put("uid", itemStackData.get("uid"));
            itemMap.put("name", itemStackData.get("name"));
            itemMap.put("mod", modName);
            itemMap.put("tags", itemStackData.get("tag"));
            itemsList.add(itemMap);
        }

        // Sort by UID to guarantee deterministic order
        itemsList.sort(Comparator.comparing(m -> (String) m.get("uid")));

        Path itemsFile = outDir.resolve(ITEMS_FILE);
        String content = GSON.toJson(itemsList);
        Files.writeString(itemsFile, content);
        LOGGER.info("Wrote {} items to {}", itemsList.size(), itemsFile.getFileName());
    }

    /**
     * Extracts JEI recipe manager index into two files
     * item_to_recipe_types.json - {"item_id": {"role":[craftingType]}}
     * Given item ID, and Role (Input/Output) get a list of available crafting types.
     * ...
     * recipe_type_to_item_to_recipe_id.json - {craftingType:{item_id:{role:[Recipe.id]}}}
     * Given CraftingType, Item ID, Role (Input/Output) get Recipe List
     * @param jeiRuntime - JEI Runtime
     * @param outDir - Output directory
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> writeIndexToFiles(IJeiRuntime jeiRuntime, Path outDir) throws IOException {
        IRecipeManager recipeManager = jeiRuntime.getRecipeManager();

        // Extract internal recipe manager structure via reflection
        Map<String, Object> recipeStructure = reflectRecipeManagerStructure(recipeManager);

        // Build indexes from extracted structure and write to files
        return buildAndWriteIndexes(recipeStructure, outDir);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> reflectRecipeManagerStructure(IRecipeManager recipeManager) {
        Map<String, Object> structure = new LinkedHashMap<>();

        try {
            // internal.recipeMaps[Role].recipeTable.map[RecipeType].uidToRecipes[item_id] -> List<Recipe>
            Field internalField = recipeManager.getClass().getDeclaredField("internal");
            internalField.setAccessible(true);
            Object internal = internalField.get(recipeManager);

            // Get recipeMaps (EnumMap)
            Field recipeMapsField = internal.getClass().getDeclaredField("recipeMaps");
            recipeMapsField.setAccessible(true);
            Map<RecipeIngredientRole, RecipeMap> recipeMaps = (Map<RecipeIngredientRole, RecipeMap>) recipeMapsField.get(internal);

            structure.put("recipeMaps", recipeMaps);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            LOGGER.error("Failed to access internal recipe manager: {}", e.getMessage());
        }

        return structure;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildAndWriteIndexes(Map<String, Object> recipeStructure, Path outDir) throws IOException {
        Path indexDir = outDir.resolve("index");
        Files.createDirectories(indexDir);

        // Initialize result structures
        Map<String, Map<String, Map<String, List<String>>>> recipeTypeToItemToRecipeId = new LinkedHashMap<>();
        Map<String, Map<String, List<String>>> itemToRecipeTypes = new LinkedHashMap<>();
        List<String> recipeIds = new ArrayList<>();
        RecipeScraper recipeScraper = new RecipeScraper();

        Map<RecipeIngredientRole, RecipeMap> recipeMaps = (Map<RecipeIngredientRole, RecipeMap>) recipeStructure.get("recipeMaps");

        // Build recipeMaps as JSON structure
        var recipeMapsJson = buildRecipeMapsJson(recipeMaps, recipeScraper);

        // Iterate over INPUT and OUTPUT role
        for (RecipeIngredientRole role : recipeMaps.keySet()) {
            if (role == RecipeIngredientRole.INPUT || role == RecipeIngredientRole.OUTPUT) {
                RecipeMap recipeMap = recipeMaps.get(role);

                try {
                    // Get recipeTable from RecipeMap
                    Field recipeTableField = recipeMap.getClass().getDeclaredField("recipeTable");
                    recipeTableField.setAccessible(true);
                    RecipeIngredientTable recipeTable = (RecipeIngredientTable) recipeTableField.get(recipeMap);

                    // Get map from RecipeIngredientTable
                    Field mapField = recipeTable.getClass().getDeclaredField("map");
                    mapField.setAccessible(true);
                    Map<RecipeType<?>, IngredientToRecipesMap<?>> ingredientTableMap =
                            (Map<RecipeType<?>, IngredientToRecipesMap<?>>)
                            mapField.get(recipeTable);

                    // Iterate over RecipeType -> IngredientToRecipesMap entries in sorted order
                    List<RecipeType<?>> sortedTypes = ingredientTableMap.keySet().stream()
                            .sorted(Comparator.comparing(t -> t.getUid().toString()))
                            .collect(Collectors.toList());

                    for (RecipeType<?> recipeType : sortedTypes) {
                        IngredientToRecipesMap<?> ingredientToRecipesMap = ingredientTableMap.get(recipeType);

                        String recipeTypeId = recipeType.getUid().toString();

                        // Get uidToRecipes from IngredientToRecipesMap
                        Field uidToRecipesField = ingredientToRecipesMap.getClass().getDeclaredField("uidToRecipes");
                        uidToRecipesField.setAccessible(true);
                        Map<String, List<?>> uidToRecipes = (Map<String, List<?>>) uidToRecipesField.get(ingredientToRecipesMap);

                        // Iterate over item UID -> recipes entries in sorted order
                        List<String> sortedUids = uidToRecipes.keySet().stream()
                                .sorted()
                                .collect(Collectors.toList());
                        for (String ingredientUid : sortedUids) {
                            List<?> recipes = uidToRecipes.get(ingredientUid);

                            // Build recipeTypeToItemToRecipeId: {recipe_type_id: {item_uid: {role: [recipe_id]}}}
                            List<String> recipeIdList = recipeTypeToItemToRecipeId.computeIfAbsent(recipeTypeId, k -> new LinkedHashMap<>())
                                    .computeIfAbsent(ingredientUid, k -> new LinkedHashMap<>())
                                    .computeIfAbsent(role.name(), k -> new ArrayList<>());

                            // Build itemToRecipeTypes: {item_uid: {role: [recipe_id_list]}}
                            List<String> itemRoleRecipeList = itemToRecipeTypes.computeIfAbsent(ingredientUid, k -> new LinkedHashMap<>())
                                    .computeIfAbsent(role.name(), k -> new ArrayList<>());

                            // Extract recipe IDs and populate both structures
                            for (Object recipe : recipes) {
                                String recipeId = recipeScraper.getRecipeId(recipe);
                                recipeIdList.add(recipeId);
                                itemRoleRecipeList.add(recipeId);
                                recipeIds.add(recipeId);
                            }
                        }
                    }
                } catch (NoSuchFieldException | IllegalAccessException e) {
                    LOGGER.error("Failed to access recipe table: {}", e.getMessage());
                }
            }
        }

        // Write files
        writeJsonFile(indexDir, "recipe_maps.json", recipeMapsJson);
        writeJsonFile(indexDir, "recipe_structure.json", recipeStructure);
        writeJsonFile(indexDir, RECIPE_TYPE_TO_ITEM + ".json", recipeTypeToItemToRecipeId);
        writeJsonFile(indexDir, ITEM_TO_RECIPE_TYPES + ".json", itemToRecipeTypes);
        writeJsonFile(indexDir, RECIPES + ".json", recipeIds);

        // Return all three structures
        Map<String, Object> results = new LinkedHashMap<>();
        results.put(RECIPE_TYPE_TO_ITEM, recipeTypeToItemToRecipeId);
        results.put(ITEM_TO_RECIPE_TYPES, itemToRecipeTypes);
        results.put(RECIPES, recipeIds);
        return results;
    }

    @SuppressWarnings("unchecked")
    private Map<?,?> buildRecipeMapsJson(
            Map<RecipeIngredientRole, RecipeMap> recipeMaps, RecipeScraper recipeScraper) {
        var recipeMapsJson = new LinkedHashMap<>();

        // Iterate over INPUT and OUTPUT role
        for (RecipeIngredientRole role : recipeMaps.keySet()) {
            if (role == RecipeIngredientRole.INPUT || role == RecipeIngredientRole.OUTPUT) {
                RecipeMap recipeMap = recipeMaps.get(role);

                try {
                    // Get recipeTable from RecipeMap
                    Field recipeTableField = recipeMap.getClass().getDeclaredField("recipeTable");
                    recipeTableField.setAccessible(true);
                    RecipeIngredientTable recipeTable = (RecipeIngredientTable) recipeTableField.get(recipeMap);

                    // Get map from RecipeIngredientTable
                    Field mapField = recipeTable.getClass().getDeclaredField("map");
                    mapField.setAccessible(true);
                    Map<RecipeType<?>, IngredientToRecipesMap<?>> ingredientTableMap =
                            (Map<RecipeType<?>, IngredientToRecipesMap<?>>) mapField.get(recipeTable);

                    var mapInside = new LinkedHashMap<>();

                    // Iterate over RecipeType -> IngredientToRecipesMap entries in sorted order
                    List<RecipeType<?>> sortedTypes = ingredientTableMap.keySet().stream()
                            .sorted(Comparator.comparing(t -> t.getUid().toString()))
                            .collect(Collectors.toList());

                    for (RecipeType<?> recipeType : sortedTypes) {
                        IngredientToRecipesMap<?> ingredientToRecipesMap = ingredientTableMap.get(recipeType);

                        String recipeTypeId = recipeType.getUid().toString();

                        // Get uidToRecipes from IngredientToRecipesMap
                        Field uidToRecipesField = ingredientToRecipesMap.getClass().getDeclaredField("uidToRecipes");
                        uidToRecipesField.setAccessible(true);
                        var uidToRecipes = (Map<String, List<?>>) uidToRecipesField.get(ingredientToRecipesMap);

                        var uidToRecipeIds = new LinkedHashMap<>();

                        // Iterate over item UID -> recipes entries in sorted order
                        List<String> sortedUids = uidToRecipes.keySet().stream()
                                .sorted()
                                .collect(Collectors.toList());
                        for (String ingredientUid : sortedUids) {
                            List<?> recipes = uidToRecipes.get(ingredientUid);

                            List<String> recipeIdList = new ArrayList<>();
                            for (Object recipe : recipes) {
                                String recipeId = recipeScraper.getRecipeId(recipe);
                                recipeIdList.add("recipe_" + recipeId);
                            }

                            uidToRecipeIds.put(ingredientUid, recipeIdList);
                        }

                        var uidToRecipesWrapper = new LinkedHashMap<>();
                        uidToRecipesWrapper.put("uidToRecipes", uidToRecipeIds);
                        mapInside.put(recipeTypeId, uidToRecipesWrapper);
                    }

                    var recipeTableMap = new LinkedHashMap<>();
                    recipeTableMap.put("map", mapInside);

                    var roleMap = new LinkedHashMap<>();
                    roleMap.put("recipeTable", recipeTableMap);

                    recipeMapsJson.put(role.name(), roleMap);
                } catch (NoSuchFieldException | IllegalAccessException e) {
                    LOGGER.error("Failed to access recipe maps for JSON: {}", e.getMessage());
                }
            }
        }

        return recipeMapsJson;
    }

    private void writeJsonFile(Path outDir, String filename, Object data) throws IOException {
        Path filePath = outDir.resolve(filename);
        String content = GSON.toJson(data);
        Files.writeString(filePath, content);
        LOGGER.info("Wrote {}", filePath.getFileName());
    }
}
