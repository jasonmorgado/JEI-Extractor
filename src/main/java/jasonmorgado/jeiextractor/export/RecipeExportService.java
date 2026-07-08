package jasonmorgado.jeiextractor.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import jasonmorgado.jeiextractor.index.IndexBuilder;
import jasonmorgado.jeiextractor.index.IndexExtractor;
import jasonmorgado.jeiextractor.scrape.RecipeScraper;
import mezz.jei.api.recipe.IRecipeLookup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates the full recipe export pipeline.
 * Can be called from the JEI plugin, a slash command, or any other trigger.
 */
public class RecipeExportService {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path baseOutDir;
    private final Path extractedJsonDir;

    /**
     * Default export to ../out relative to the run directory.
     */
    public RecipeExportService() {
        this(Paths.get("../out"));
    }

    /**
     * Export to a custom base directory.
     *
     * @param baseOutDir The base output directory (contains extracted-json/, one_per_type.json, etc.)
     */
    public RecipeExportService(Path baseOutDir) {
        this.baseOutDir = baseOutDir;
        this.extractedJsonDir = baseOutDir.resolve("extracted-json");
    }

    /**
     * Runs the full export pipeline: items, one-per-type, per-type files, and indexes.
     */
    public void runExport(IJeiRuntime jeiRuntime) {
        var recipeManager = jeiRuntime.getRecipeManager();

        try {
            Files.createDirectories(extractedJsonDir);

            var extractor = new IndexExtractor();
            extractor.writeItemsFile(jeiRuntime, extractedJsonDir);

            writeOnePerTypeFile(recipeManager, baseOutDir);

            writeRecipeTypesFiles(recipeManager, extractedJsonDir);

            var indexBuilder = new IndexBuilder();
            indexBuilder.buildIndexes(extractedJsonDir.resolve("recipe_types"), extractedJsonDir);

            Collection<RecipeType<?>> recipeTypes = recipeManager.createRecipeCategoryLookup()
                    .get()
                    .map(IRecipeCategory::getRecipeType)
                    .collect(Collectors.toList());
            writeRecipeTypesFile(baseOutDir, recipeTypes);

        } catch (IOException e) {
            LOGGER.error("Failed to write recipe data: {}", e.getMessage());
        }
    }

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
            var recipe = sortedRecipes.get(0);
            Map<String, Object> recipeMap = scraper.recipeToMap(recipe, category);
            onePerType.put(type.getUid().toString(), recipeMap);
        }
        return onePerType;
    }

    private void writeRecipeTypesFile(Path outDir, Collection<RecipeType<?>> recipeTypes) throws IOException {
        Path typesFile = outDir.resolve("recipe_types.txt");
        Files.writeString(typesFile, recipeTypes.stream()
                .map(RecipeType::toString)
                .collect(Collectors.joining("\n")));
        LOGGER.info("JEI Recipe Types written to {}", typesFile.toAbsolutePath());
    }

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

            Path typeFile = recipeTypesDir.resolve(typeId + ".json");
            String content = GSON.toJson(recipeJsonList);
            Files.writeString(typeFile, content);
            LOGGER.info("Wrote {} recipes to {}", recipeJsonList.size(), typeFile.getFileName());
        }
    }
}
