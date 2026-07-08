package jasonmorgado.jeiextractor.plugin;

import com.mojang.logging.LogUtils;
import jasonmorgado.jeiextractor.JEIExtractorMod;
import jasonmorgado.jeiextractor.export.RecipeExportService;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;


@JeiPlugin
public class JEIExtractorPlugin implements IModPlugin {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public @NotNull ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(JEIExtractorMod.MODID, "jei_plugin");
    }

    /**
     * This function runs when the game initially loads, after mods add their recipes to the game
     * @param jeiRuntime - The JEI Runtime which includes the RecipeManager
     */
    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        LOGGER.info("Starting JEI recipe export...");
        var exportService = new RecipeExportService();
        exportService.runExport(jeiRuntime);
        LOGGER.info("JEI recipe export complete.");
    }

}
