# Exported Files

The JSON export currently saves to an out/ file outside the minecraft directory. Why? Because it's easier to find in IntelliJ.

Mind you I haven't tested this with a compiled jar yet.

This outputs:
- items.json - A list of item details with their uids. Perhaps should be a dict keyed on the uids themselves.
- recipe_types/ which contains a json file per recipe type. Each containing a list of recipe JSON.
- two index files:
  - recipe_type_index.json
    - Dict mapping item uid -> role -> list of RecipeTypes. Useful for knowing which tabs needed when clicking on an item.
  - recipe_index.json
    - Dict mapping RecipeType -> item uid -> role -> Recipe Indexes from their recipe list.

items.json can be used to populate JEI's item grid on the right side panel.

recipe_type_index.json can be used to fetch what RecipeTypes are needed when clicking on an item, to fetch its recipes.

recipe_index.json allows us to fetch recipes for a given RecipeType, when we select a tab after searching for an item's recipes. 

Splitting the index into separate files like this also allows us to search for all recipes of a RecipeType using recipe_index.json.
