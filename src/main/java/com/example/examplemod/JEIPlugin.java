package com.example.examplemod;

import com.mojang.logging.LogUtils;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.recipe.IRecipeLookup;
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
        // Runs when the world is loaded and JEI's recipe manager is available
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
        // Given a RecipeType, extract all recipes of that type and write to a JSON file
        try {
            String fileName = type.getUid().getPath() + ".json";
            Path file = outDir.resolve(fileName);

            IRecipeLookup<R> recipeLookup = recipeManager.createRecipeLookup(type);
            var recipe = recipeLookup.get().findFirst().orElse(null);

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

    /**
     * Converts a Recipe object to a map of simple key-value pairs.
     */
    private Map<String, Object> recipeToMap(Object recipe) {
        Map<String, Object> map = new HashMap<>();
        map.put("_type", recipe.getClass().getSimpleName());

        try {
            java.lang.reflect.Field[] fields = recipe.getClass().getDeclaredFields();
            for (java.lang.reflect.Field field : fields) {
                field.setAccessible(true);
                Object value = field.get(recipe);
                map.put(field.getName(), String.valueOf(value));
            }
        } catch (Exception e) {
            map.put("_error", e.getMessage());
        }

        return map;
    }

}
