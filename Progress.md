# Progress

So far I've gathered that JEI exposes a RecipeManager that can be accessed by a mod acting as a JEI plugin.

This provides a way to get a list of `RecipeType` objects used in JEI. 

It also provides `createRecipeLookup(recipe_type)` which returns a `IRecipeLookup<R>`. This is some kind of storage for the list of all recipes for a particular type.

Unfortunately there are multiple recipe types, and many mods add their own types. These types have their own classes, and store data a little differently.

I've found that I can inspect the properties of each of these with the IntelliJ debugger:
![IntelliJ Debugger displaying a shaped crafting recipe](debugger_shaped_recipe.png)


Each object has many properties. So far I've tried using Java [reflection](https://stackoverflow.com/questions/37628/what-is-reflection-and-why-is-it-useful) to inspect the properties of the class, and each property of those classes. However, it seems many of these objects are deeply, deeply nested with unusable data, causing a stack overflow when iterating over them. 

This will require manually parsing certain objects. such as the `Ingredient`. 

An additional reason for this, Java doesn't support just fetching arbitrary properties of classes. I gotta go through getter methods that may not be obviously named.

For example, what I've found in the ShapedRecipe class to contain:

ShapedRecipe
- width - `int`
- height - `int`
- recipeItems - `Ingredient[]`
  - Accessible via `recipe.getIngredients()`
- result - `ItemStack`
- id - `ResourceLocation`
  - `.toString()` provides a name like `minecraft:brown_bed`
- group - `string`
  - Used for similar recipes perhaps? I noticed a group "bed" for beds of different colors.


Ingredient
- values - Stores tags for input ingredients
    - I don't think this is readable, `Ingredient.toJson()` throws an error
- itemStacks - `ItemStack[]`
    - Stores all possible inputs for a recipe.
    - Can get it with `Ingredient.getItems()`
- isVanilla - `bool`


So far it seems we can only extract itemStacks from JEI Ingredients, not the values. This provides a list of items we can use in each input slot, which might cause a lot of duplication. So for each Ingredient that has multiple possible inputs, I generate a kind of tag placeholder in out/ingredients.json. It has an arbitrary id "ingredient001" associated with a distinct list of ingredients. 

For example, the JSON for crafting a piston ends up looking like:

```json
 {
    "width": 3,
    "_type": "ShapedRecipe",
    "ingredients": [
      "ingredient_1",
      "ingredient_1",
      "ingredient_1",
      "cobblestone",
      "iron_ingot",
      "cobblestone",
      "cobblestone",
      "redstone",
      "cobblestone"
    ],
    "id": "minecraft:piston",
    "result_count": 1,
    "height": 3,
    "group": ""
  },
```

With a width and height of 3, we can tell the recipe takes the whole 3x3 crafting grid. And lists required items left to right, row by row.

Here we see the top row is "ingredient_1" where you'd expect to put wooden planks, then the other ingredients below.

In ingredients.json there's an entry that looks like this:

```json
"ingredient_1": [
    "oak_planks",
    "spruce_planks",
    "birch_planks",
    "jungle_planks",
    "acacia_planks",
    "dark_oak_planks",
    "crimson_planks",
    "warped_planks",
    "mangrove_planks"
  ],
```

This indicates the recipe for the piston in JEI may iterate over this list when displaying the ingredients. This shows the user you can use any type of wood here.

So far I've needed a specific function to handle ingesting ShapedRecipes like this. Using reflection has mixed results so far.

With anvil:
```json
 {
    "outputs": "[1 wooden_sword]",
    "rightInputs": "[1 wooden_sword]",
    "_type": "AnvilRecipe",
    "leftInputs": "[1 wooden_sword]"
},
```
Seems to pull fine. However, with blasting:

```json
{
    "_type": "BlastingRecipe"
},
``` 

Imagine a list of 20 of those. Not helpful.

Interestingly, brewing.json has items like this:
```json
{
    "potionInputs": "[1 potion]",
    "potionOutput": "1 potion",
    "hashCode": "-1243623584",
    "_type": "JeiBrewingRecipe",
    "ingredients": "[1 magma_cream]",
    "brewingRecipeUtil": "mezz.jei.common.plugins.vanilla.brewing.BrewingRecipeUtil@400c0d26"
},
```

Potions are particularly interesting to process, as they all use the same item, with different metadata attached to them, differentiating potions.

Since Minecraft 1.13, this is stored in [NBT Data](https://minecraft.wiki/w/NBT_format). In Minecraft 1.12 and earlier it was some other format. I'm currently testing on 1.19.2. I believe it also handles enchantments, and other data.

Minecraft provides `PotionUtils` to help parse them, I might have to dig into this later.
