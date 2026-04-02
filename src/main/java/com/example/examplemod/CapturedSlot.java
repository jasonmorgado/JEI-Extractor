package com.example.examplemod;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.forge.ForgeTypes;
import mezz.jei.api.gui.builder.IIngredientAcceptor;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotTooltipCallback;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;

import java.util.*;
import java.util.stream.Collectors;

/**
 * A helper class implementing IRecipeSlotBuilder that records ingredient data from recipe slots.
 * This class captures all items and fluids added to a slot, along with the slot's position
 * and role (input/output/catalyst, etc.) in the recipe.
 *
 * Rendering-related methods are ignored as this is designed for data extraction, not display.
 */
public class CapturedSlot implements IRecipeSlotBuilder {

    private final RecipeIngredientRole role;
    private final int x;
    private final int y;
    private final List<ItemStack> items = new ArrayList<>();
    private final List<FluidStack> fluids = new ArrayList<>();

    public CapturedSlot(RecipeIngredientRole role, int x, int y) {
        this.role = role;
        this.x = x;
        this.y = y;
    }

    @Override
    public <I> IRecipeSlotBuilder addIngredients(IIngredientType<I> ingredientType, List<I> ingredients) {
        if (ingredientType == VanillaTypes.ITEM_STACK) {
            ingredients.forEach(
                i -> items.add((ItemStack) i)
            );
        } else if (ingredientType == ForgeTypes.FLUID_STACK) {
            ingredients.forEach(
                    i -> fluids.add((FluidStack) i)
            );
        }
        return this;
    }

    @Override
    public IRecipeSlotBuilder addIngredients(Ingredient ingredient) {
        items.addAll(Arrays.asList(ingredient.getItems()));
        return this;
    }

    @Override
    public <I> IRecipeSlotBuilder addIngredient(IIngredientType<I> ingredientType, I ingredient) {
        if (ingredientType == VanillaTypes.ITEM_STACK) {
            items.add((ItemStack) ingredient);
        } else if (ingredientType == ForgeTypes.FLUID_STACK) {
            fluids.add((FluidStack) ingredient);
        }
        return this;
    }

    @Override
    public IRecipeSlotBuilder addIngredientsUnsafe(List<?> ingredients) {
        // Capture whatever ingredients we can identify
        for (Object ingredient : ingredients) {
            if (ingredient instanceof ItemStack) {
                items.add((ItemStack) ingredient);
            } else if (ingredient instanceof FluidStack) {
                fluids.add((FluidStack) ingredient);
            }
        }
        return this;
    }

    @Override
    public IRecipeSlotBuilder addItemStack(ItemStack itemStack) {
        items.add(itemStack);
        return this;
    }

    @Override
    public IRecipeSlotBuilder addItemStacks(List<ItemStack> itemStacks) {
        items.addAll(itemStacks);
        return this;
    }

    @Override
    public IRecipeSlotBuilder addFluidStack(Fluid fluid, long amount) {
        // Store fluid info (you may want to create a FluidInfo class to store amount)
        return this;
    }

    @Override
    public IRecipeSlotBuilder addFluidStack(Fluid fluid, long amount, CompoundTag tag) {
        // Store fluid info with tag
        return this;
    }

    @Override
    public IRecipeSlotBuilder addTooltipCallback(IRecipeSlotTooltipCallback tooltipCallback) {
        // no-op, tooltip handling
        return this;
    }

    @Override
    public IRecipeSlotBuilder setSlotName(String slotName) {
        // no-op, naming is for UI lookup
        return this;
    }

    @Override
    public IRecipeSlotBuilder setBackground(IDrawable background, int xOffset, int yOffset) {
        // no-op, rendering detail
        return this;
    }

    @Override
    public IRecipeSlotBuilder setOverlay(IDrawable overlay, int xOffset, int yOffset) {
        // no-op, rendering detail
        return this;
    }

    @Override
    public IRecipeSlotBuilder setFluidRenderer(long capacity, boolean showCapacity, int width, int height) {
        // no-op, rendering detail
        return this;
    }

    @Override
    public <T> IRecipeSlotBuilder setCustomRenderer(IIngredientType<T> ingredientType, IIngredientRenderer<T> ingredientRenderer) {
        // no-op, rendering detail
        return this;
    }

    public RecipeIngredientRole getRole() { return role; }
    public List<ItemStack> getItems() { return items; }
    public List<FluidStack> getFluids() { return fluids; }
    public int getX() { return x; }
    public int getY() { return y; }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", role.toString());
        map.put("x", x);
        map.put("y", y);
        // Should this use the special ItemStack extractor from RecipeScraper?
        map.put("items", items.stream().map(ItemStack::toString).collect(Collectors.toList()));
        map.put("fluids", fluids.stream().map(FluidStack::toString).collect(Collectors.toList()));
        return map;
    }
}