# Progress

So far I've gathered that JEI exposes a RecipeManager that can be accessed by a mod acting as a JEI plugin.

This provides a way to get a list of `RecipeType` objects used in JEI. 

It also provides `createRecipeLookup(recipe_type)` which returns a `IRecipeLookup<R>`. This is some kind of storage for the list of all recipes for a particular type.

Unfortunately there are multiple recipe types, and many mods add their own types. These types have their own classes, and store data a little differently.

I've found that I can inspect the properties of each of these with the IntelliJ debugger:
![IntelliJ Debugger displaying a shaped crafting recipe](debugger_shaped_recipe.png)


Each object has many properties. So far I've tried using Java [reflection](https://stackoverflow.com/questions/37628/what-is-reflection-and-why-is-it-useful) to inspect the properties of the class, and each property of those classes. However, it seems many of these objects are deeply, deeply nested with unusable data, causing a stack overflow when iterating over them. Particularly the `Ingredient` class.


For example, I've found the ShapedRecipe class to contain the following useful information:

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

So far I've needed a specific function to handle ingesting ShapedRecipes like this. 

## Generic Recipe Scraping

I've recently developed a function to take a Recipe object, of arbitrary type, and scrape the properties off the instance. This involves listing the properties of the class using `getDeclaredFields()`. I also had to iterate over the parent classes to get ALL the fields available to the recipe being inspected.

For example, the `BlastingRecipe` class doesn't have any data exposed by `getDeclaredFields`, because its parent class `AbstractCookingRecipe` has all the information. This is because cooking things in a Minecraft Furnace is similar to the Blast Furnace or Smoker.

This provides me with a relatively verbose, but very usable JSON output from any arbitrary Recipe. As long as I can properly handle the output elsewhere.

Looking at crafting.json, I see my custom formatted shaped crafting is combined with scraped shapeless crafting. Unfortunately it doesn't work quite as well:
```json
{
  "result": {
    "item": "mushroom_stew",
    "capNBT": {
      "Count": "1b",
      "id": "minecraft:mushroom_stew"
    },
    "_type": "ItemStack",
    "count": 1
  },
  "_type": "ShapelessRecipe",
  "ingredients": [
    {
      "INVALIDATION_COUNTER": "1",
      "values": "[Lnet.minecraft.world.item.crafting.Ingredient$Value;@358ebd61",
      "isVanilla": "true",
      "_type": "Ingredient",
      "invalidationCounter": "0",
      "itemStacks": "[Lnet.minecraft.world.item.ItemStack;@75e355e",
      "EMPTY": {
        "_type": "Ingredient",
        "items": []
      }
    },
    {
      "INVALIDATION_COUNTER": "1",
      "values": "[Lnet.minecraft.world.item.crafting.Ingredient$Value;@485e0996",
      "isVanilla": "true",
      "_type": "Ingredient",
      "invalidationCounter": "0",
      "itemStacks": "[Lnet.minecraft.world.item.ItemStack;@4db203fb",
      "EMPTY": {
        "_type": "Ingredient",
        "items": []
      }
    },
    {
      "INVALIDATION_COUNTER": "1",
      "values": "[Lnet.minecraft.world.item.crafting.Ingredient$Value;@d97ca99",
      "isVanilla": "true",
      "_type": "Ingredient",
      "invalidationCounter": "0",
      "itemStacks": "[Lnet.minecraft.world.item.ItemStack;@7b95eab0",
      "EMPTY": {
        "_type": "Ingredient",
        "items": []
      }
    }
  ],
  "isSimple": "true",
  "id": "minecraft:mushroom_stew",
  "group": ""
},
```

That's the next thing that needs fixing.


Interestingly I also get NBT data from these items, allowing me to get information on potions

## NBT Data

Potions are particularly interesting to process, as they all use the same item, with different metadata attached to them, differentiating potions.

Since Minecraft 1.13, this is stored in [NBT Data](https://minecraft.wiki/w/NBT_format). In Minecraft 1.12 and earlier it was some other format. I'm currently testing on 1.19.2. I believe it also handles enchantments, and other data.

Minecraft provides `PotionUtils` to help parse them, I might have to dig into this later.

With my general-purpose scraper, I've been able to capture NBT-Data off of the ItemStack. It's a long JSON though. The following JSON describes combining a potion of fire resistance with redstone dust to increase its duration:
```json
{
  "potionInputs": [
    {
      "item": "potion",
      "capNBT": {
        "Count": "1b",
        "id": "minecraft:potion",
        "tag": {
          "Potion": "minecraft:fire_resistance"
        }
      },
      "_type": "ItemStack",
      "count": 1,
      "tag": {
        "Potion": "minecraft:fire_resistance"
      }
    }
  ],
  "potionOutput": {
    "item": "potion",
    "capNBT": {
      "Count": "1b",
      "id": "minecraft:potion",
      "tag": {
        "Potion": "minecraft:long_fire_resistance"
      }
    },
    "_type": "ItemStack",
    "count": 1,
    "tag": {
      "Potion": "minecraft:long_fire_resistance"
    }
  },
  "hashCode": "-796919496",
  "_type": "JeiBrewingRecipe",
  "ingredients": [
    {
      "item": "redstone",
      "capNBT": {
        "Count": "1b",
        "id": "minecraft:redstone"
      },
      "_type": "ItemStack",
      "count": 1
    }
  ],
  "brewingRecipeUtil": "mezz.jei.common.plugins.vanilla.brewing.BrewingRecipeUtil@b7461ac"
},
```

Interestingly, some numbers in NBT are followed by a character indicating type. The "1b" indicates a count of 1 as we might expect.


## Drawing the UI

Looks like IRecipeCategory has information on how the crafting UI is put together.

IRecipeCategory
- Has method `getBackground()` which returns an `IDrawable` background. Something something rendering?
- `getIcon()` returns an icon for the category. Used in tabs.
-  

IDrawable
- JEI-Specific drawing class

Loading images from JEI may require rendering the IDrawable, redirecting the output to an off-screen framebuffer, and read the pixels into an image file. 

Similarly to how some mods scrape block renders from the game for wikis.

Extracting this information is likely extremely complicated, it should wait until I have a POC demonstrating this pipeline actually works.
