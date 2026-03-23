# TODO

## Quick POC

- Test generic scraper against ShapedRecipe, and make it work against ShapelessRecipe.
- Investigate options for deploying a frontend
  - Perhaps GitHub pages could host a bare-bones frontend? Should be fine if the JSON can be bundled with the frontend code.
- Deploy frontend with simple input -> output with text.
- List of available items
- Support clicking on an item and getting recipes
  - Build two-way index for list of items in frontend

## Proper UI
- Render item/block icons in frontend
  - Scrape images with an existing mod
- Render simple icon -> icon for recipes
- Download Crafting Table background
- Set up ShapedCrafting / ShapelessCrafting with Crafting UI

## Extra stuff
- Test against a mod that adds modded recipes, such as Create.
- Test building mod to a jar and adding it to another modpack.
- Figure out how to get Minecraft to open the world when I hit debug button.
- Dynamically pull UI building information from IRecipeCategory from JEI.
- Look into loading tags from Forge.
