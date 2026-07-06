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
import net.minecraft.world.item.crafting.Recipe;
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
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public @NotNull ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "jei_plugin");
    }

    /**
     * This function runs when the game initially loads, after mods add their recipes to the game
     * @param jeiRuntime - The JEI Runtime which includes the RecipeManager
     */
    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        var recipeManager = jeiRuntime.getRecipeManager();
        // outDir is the basic output folder for testing things. Contains extracted-json.
        var outDir = Paths.get("../out");
        // extractedJsonDir is the Web Export, matches the frontend.
        var extractedJsonDir = outDir.resolve("extracted-json");

        try {
            Files.createDirectories(extractedJsonDir);
            var extractor = new IndexExtractor();
            extractor.writeItemsFile(jeiRuntime, extractedJsonDir); // -> items.json

            writeOnePerTypeFile(recipeManager, outDir); // one_per_type.json
            // Writing recipe_types/ to out/extracted-json/
            writeRecipeTypesFiles(recipeManager, extractedJsonDir); // -> extracted-json/recipe_types/{mod}_{type}.json

            // Build indexes from recipe types into extracted-json/ directly
            // Generates recipe_index.json and recipe_type_index.json
            Path recipeTypesDir = extractedJsonDir.resolve("recipe_types");
            var indexBuilder = new IndexBuilder();
            indexBuilder.buildIndexes(recipeTypesDir, extractedJsonDir);

            Collection<RecipeType<?>> recipeTypes = recipeManager.createRecipeCategoryLookup()
                    .get()
                    .map(IRecipeCategory::getRecipeType)
                    .collect(Collectors.toList());
            writeRecipeTypesFile(outDir, recipeTypes);
        } catch (IOException e) {
            LOGGER.error("Failed to write recipe data: {}", e.getMessage());
        }
    }


    /**
     * Writes one_per_type.json which contains a list of Recipes, one of each type.
     * @param recipeManager  JEI Recipe Manager
     * @param outDir Directory to write to
     * @throws IOException Who knows?
     */
    private void writeOnePerTypeFile(IRecipeManager recipeManager, Path outDir) throws IOException {
        Collection<RecipeType<?>> recipeTypes = recipeManager.createRecipeCategoryLookup()
                .get()
                .map(IRecipeCategory::getRecipeType)
                .collect(Collectors.toList());

        Map<String, Map<String, Object>> onePerType = buildOnePerTypeMap(recipeManager, recipeTypes);
        Path onePerTypeFile = outDir.resolve("one_per_type.json");
        String onePerTypeContent = GSON.toJson(onePerType);
        Files.writeString(onePerTypeFile, onePerTypeContent);
        LOGGER.info("Wrote one recipe per type to {}", onePerTypeFile.getFileName());
    }

    /**
     * Fetches one recipe per RecipeType, nondeterministically (It's random)
     * Good for debugging through Recipe objects to see what's inside.
     *
     * @param recipeManager - JEI Recipe Manager
     * @param recipeTypes   - List of RecipeTypes
     * @return A Map of item uids -> JSON containing scraped Recipe data.
     */
    private Map<String, Map<String, Object>> buildOnePerTypeMap(IRecipeManager recipeManager, Collection<RecipeType<?>> recipeTypes) {
        RecipeScraper scraper = new RecipeScraper();
        Map<String, Map<String, Object>> onePerType = new LinkedHashMap<>();

        List<IRecipeCategory<?>> categories = recipeManager.createRecipeCategoryLookup()
                .get()
                .sorted(Comparator.comparing(c -> c.getRecipeType().getUid().toString()))
                .toList();

        for (IRecipeCategory<?> category : categories) {
            RecipeType<?> type = category.getRecipeType();
            IRecipeLookup<?> recipeLookup = recipeManager.createRecipeLookup(type);
            List<?> sortedRecipes = recipeLookup.get()
                    .sorted(Comparator.comparing(r -> scraper.getRecipeId(r)))
                    .toList();
            if (sortedRecipes.isEmpty()) {
                continue;
            }
            // Note: child Recipes cannot be cast to Recipe
            var recipe = sortedRecipes.get(0);
            Map<String, Object> recipeMap = scraper.recipeToMap(recipe, category);
            onePerType.put(type.getUid().toString(), recipeMap);
        }
        return onePerType;
    }

    /**
     * Writes recipe_types.txt containing a flat list of the types
     *
     * @param outDir
     * @param recipeTypes
     * @throws IOException
     */
    private void writeRecipeTypesFile(Path outDir, Collection<RecipeType<?>> recipeTypes) throws IOException {
        Path typesFile = outDir.resolve("recipe_types.txt");
        Files.writeString(typesFile, recipeTypes.stream()
                .map(RecipeType::toString)
                .collect(Collectors.joining("\n")));
        LOGGER.info("JEI Recipe Types written to {}", typesFile.toAbsolutePath());
    }

    /**
     * Writes a separate JSON file for each RecipeType containing all recipes of that type.
     * Files are written to out/recipe_types/ directory, named after the recipe type UID.
     *
     * @param recipeManager JEI Recipe Manager
     * @param outDir Base output directory
     * @throws IOException If directory or files cannot be created
     */
    private void writeRecipeTypesFiles(IRecipeManager recipeManager, Path outDir) throws IOException {
        Path recipeTypesDir = outDir.resolve("recipe_types");
        Files.createDirectories(recipeTypesDir);

        List<IRecipeCategory<?>> categories = recipeManager.createRecipeCategoryLookup()
                .get()
                .sorted(Comparator.comparing(c -> c.getRecipeType().getUid().toString()))
                .toList();

        RecipeScraper scraper = new RecipeScraper();

        for (IRecipeCategory<?> category : categories) {
            RecipeType<?> type = category.getRecipeType();
            String typeId = type.getUid().toString();
            typeId = typeId.replace(":", "_");
            IRecipeLookup<?> recipeLookup = recipeManager.createRecipeLookup(type);

            // For Recipe in Recipes for this RecipeType, extract Recipe JSON
            List<Map<String, Object>> recipeJsonList = new ArrayList<>();
            List<?> sortedRecipes = recipeLookup.get()
                    .sorted(Comparator.comparing(r -> scraper.getRecipeId(r)))
                    .toList();
            for (var recipe : sortedRecipes) {
                Map<String, Object> recipeJson = scraper.recipeToMap(recipe, category);
                recipeJsonList.add(recipeJson);
            }

            if (recipeJsonList.isEmpty()) {
                continue;
            }

            // Write List to out/recipe_types/type_id.json
            Path typeFile = recipeTypesDir.resolve(typeId + ".json");
            String content = GSON.toJson(recipeJsonList);
            Files.writeString(typeFile, content);
            LOGGER.info("Wrote {} recipes to {}", recipeJsonList.size(), typeFile.getFileName());
        }
    }

}
