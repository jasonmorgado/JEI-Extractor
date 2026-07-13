package jasonmorgado.jeiextractor.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.mojang.logging.LogUtils;
import jasonmorgado.jeiextractor.scrape.RecipeScraper;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.constants.VanillaTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Extracts JEI's ingredient data into structured JSON files.
 *
 * <p>Writes:
 * <ul>
 *   <li>{@code items.json} — all registered items with id, name, mod, and tags</li>
 * </ul>
 */
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


}
