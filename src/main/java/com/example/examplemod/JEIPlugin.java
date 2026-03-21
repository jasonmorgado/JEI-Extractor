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

        Path output = Paths.get("recipe_types.txt");
        try {
            Files.writeString(output, recipeTypes.stream()
                    .map(RecipeType::toString)
                    .collect(Collectors.joining("\n")));
            LOGGER.info("JEI Recipe Types written to {}", output.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("Failed to write recipe types: {}", e.getMessage());
        }
    }
}
