package com.example.examplemod;

import mezz.jei.api.gui.builder.IIngredientAcceptor;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.material.Fluid;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A scraper implementing IRecipeLayoutBuilder that records all recipe slot information.
 * Instead of rendering a recipe layout, this builder captures the slots and their ingredients
 * so they can be extracted to JSON.
 */
@MethodsReturnNonnullByDefault // Java nonsense lol
@ParametersAreNonnullByDefault
public class CapturingLayoutBuilder implements IRecipeLayoutBuilder {
    private final List<CapturedSlot> slots = new ArrayList<>();

    @Override
    public IRecipeSlotBuilder addSlot(RecipeIngredientRole role, int x, int y) {
        CapturedSlot slot = new CapturedSlot(role, x, y);
        slots.add(slot);
        return slot;
    }

    @Override
    public IIngredientAcceptor<?> addInvisibleIngredients(RecipeIngredientRole recipeIngredientRole) {
        // Return a no-op acceptor for invisible ingredients
        return new NoOpIngredientAcceptor();
    }

    @Override
    public void moveRecipeTransferButton(int x, int y) {
        // no-op, don't care about UI positioning
    }

    @Override
    public void setShapeless() {
        // no-op, don't care about shaped/shapeless indicator
    }

    @Override
    public void setShapeless(int posX, int posY) {
        // no-op, don't care about shaped/shapeless indicator with position
    }

    @Override
    public void createFocusLink(IIngredientAcceptor<?>... slots) {
        // no-op, don't care about focus linking
    }

    public List<CapturedSlot> getSlots() {
        return slots;
    }

    public List<CapturedSlot> getSlots(RecipeIngredientRole role) {
        return slots.stream()
                .filter(s -> s.getRole() == role)
                .collect(Collectors.toList());
    }

    /**
     * A no-op implementation of IIngredientAcceptor for handling invisible ingredients.
     * Invisible ingredients are important for recipe lookup but not displayed visually.
     * Currently discarding them to appease the syntax errors, TODO do something with this later.
     */
    @MethodsReturnNonnullByDefault
    @ParametersAreNonnullByDefault
    private static class NoOpIngredientAcceptor implements IIngredientAcceptor<NoOpIngredientAcceptor> {
        @Override
        public <I> NoOpIngredientAcceptor addIngredients(mezz.jei.api.ingredients.IIngredientType<I> ingredientType, List<I> ingredients) {
            return this;
        }

        @Override
        public <I> NoOpIngredientAcceptor addIngredient(mezz.jei.api.ingredients.IIngredientType<I> ingredientType, I ingredient) {
            return this;
        }

        @Override
        public NoOpIngredientAcceptor addIngredientsUnsafe(List<?> ingredients) {
            return this;
        }

        @Override
        public NoOpIngredientAcceptor addFluidStack(Fluid fluid, long amount) {
            return this;
        }

        @Override
        public NoOpIngredientAcceptor addFluidStack(Fluid fluid, long amount, CompoundTag tag) {
            return this;
        }
    }
}
