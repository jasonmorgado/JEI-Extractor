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


---

March 24th

I've been digging further into how the internal manager stores the recipes.
It has the following structure:
- recipeManager (RecipeManager)
  - internal (RecipeManagerInternal)
    - recipeTypeDataMap (RecipeTypeDataMap)
      - uidMap Map(ResourceLocation -> RecipeTypeData)
        - ResourceLocation "minecraft:crafting"
        - RecipeTypeData
          - recipeCategory
          - recipeCategoryCatalysts ?
          - recipes `List<Recipe>`
    - recipeCategoryComparator - ?
    - recipeMaps (RecipeIngredientRole -> RecipeMap)
      - RecipeIngredientRole (One of "INPUT", "OUTPUT", "CATALYST", "RENDER_ONLY")
      - RecipeMap
        - recipeTable (RecipeIngredientTable) (RecipeType -> uid -> List<Recipe>)
          - map (RecipeType -> IngredientToRecipesMap)
            - RecipeType
              - uid "minecraft:fuel"
              - recipeClass - java class for type
            - IngredientToRecipesMap
              - uidToRecipes
                - uid key "minecraft:yellow_wool"
                - value - `List<Recipe>`
        - ingredientUidToCategoryMap (SetMultiMap) (uid -> RecipeType)
          - map uid -> RecipeType
        - categoryCatalystUidToRecipeCategoryMap - ?
        - recipeTypeComparator - ?
        - registeredIngredients
          - orderedTypes
            - empty list of VanillaTypes
            - empty list of ForgeTypes
          - typeToInfo
            - Types -> IngredientInfo
              - IngredientInfo
                - ???
          - classToType
        - role (RecipeIngredientRole) - "INPUT"
  
Roles define what they map
- INPUT is input -> recipes,
- OUTPUT is output -> recipes
- CATALYST appears to map crafting blocks to their RecipeTypes. 
  - For example, minecraft:furnace -> FuelingRecipe and SmeltingRecipe
- RENDER_ONLY doesn't appear to have anything, but I suspect it's used to render blocks / entities for recipes that you can't click to get the recipe? None exist in vanilla.

Interesting that I could get item -> recipe mappings from here, but I still need to generically get something usable from the recipes.

I've also found out that RecipeCategory uses a setRecipe function with a RecipeLayoutBuilder to define which inputs go in what locations on the UI.
With builder.add_slot(Role, x, y).

And all the RecipeCategories have a common behavior for when the recipe is drawn on the UI. 
They have a setRecipe method that takes a builder, recipe, and focuses.
For example: https://github.com/Creators-of-Create/Create/blob/0a17a7243c3e5e6e3ceb34450d9c6df240af1b83/src/main/java/com/simibubi/create/compat/jei/category/DeployingCategory.java#L31

They call builder.addSlot with the ingredient Role (input/output), and the x, y coordinates where they get drawn on the UI.

If I made a custom builder class that recorded the inputs for addSlot() for each recipe, 
I could figure out which ingredients are inputs/outputs for any arbitrary recipe, 
and where to draw them on the UI.

After that, we just lack the background which is stored in the IRecipeCategory, 
but we may be able to extract those as well.

Hard to plan things out without this builder, but first thing would be to extract each recipe with every input/output. 
Then on the frontend display the inputs/outputs plain-text.
We'd want to assemble a multimap of the recipes with input -> recipe and output -> recipe mappings. 
Recipes themselves point towards inputs/outputs so we have input <-> recipe <-> output.

That'll be work.

Then we'll want to look into extracting icons and extracting UI backgrounds.

Icons first so we can upgrade it to be just:
Input Icons -> (Catalyst Options) -> Output Icons
And just list em out.

Animated UI backgrounds could be a pain in the ass. GIFs may also be heavier in storage and network traffic. 
Mod wikis tend to use compressed webp files to avoid large file size / load times.

A locally hosted frontend might be fine with animated textures.

Then I can look into rendering it on the frontend.

For future reference 
https://github.com/mezz/JustEnoughItems
https://github.com/Creators-of-Create/Create/tree/mc1.21.1/dev


---

## Slot Extractor
Jesus I should organize these research notes into separate files or something.

I've started building out my classes for extracting slots from setRecipe.

I've created CapturingLayoutBuilder which replaces the builder class of the correct category, 
and CapturedSlot which is used in place of regular slots. 
The CapturedSlot is needed since they call slot.addIngredients() on it to attach the actual ingredients.

$0.54 of carefully calculated Claude Code edits later, and we have an output JSON that looks like this:

(Most inputs omitted bc it's a long JSON)
```json
{
  "minecraft:piston": [
    {
      "role": "OUTPUT",
      "x": 95,
      "y": 19,
      "items": [
        "1 piston"
      ],
      "fluids": []
    },
    {
      "role": "INPUT",
      "x": 1,
      "y": 1,
      "items": [
        "1 oak_planks",
        "1 spruce_planks",
        "1 birch_planks",
        "1 jungle_planks",
        "1 acacia_planks",
        "1 dark_oak_planks",
        "1 crimson_planks",
        "1 warped_planks",
        "1 mangrove_planks"
      ],
      "fluids": []
    }
  ]
}
```

The key here is the recipe.id, which happens to be the output item in this case. 
I find that a bit odd, but I'll have to find edge cases for it.

We have inputs and outputs with ItemStack info like a stack of the items.
I may want to add my generic parser to it, so we can extract more information out of it.

There is a LOT of duplication in this JSON output, as there are only 9 input slots and one output slot in the crafting grid.
But we redefine the x,y pairs for each recipe.

I'll want to have a map of some sort defining unique slot locations and swap em out. Same with the items list.
