MMCECERecipeAdapterEvents.register(event => {
  event.create('mmcece:test_missing_resource_ports', 'minecraft:furnace')
    .setImportId('crt_naming_smoke')
    .addEnergyPerTickInput(16)
    .addEnergyPerTickInput(16)
    .setDuration(20)
    .setPriority(5)
    .setSourceRecipes(['minecraft:iron_ingot_from_smelting_iron_ore'])
    .build()

  event.create('mmcece:test_missing_resource_ports', 'minecraft:furnace')
    .setImportId('not_built')
})

ServerEvents.recipes(event => {
  if (typeof event.recipes.mmcece.machine === 'function' || typeof event.recipes.machine === 'function'
      || typeof event.recipes['mmcece:machine'] === 'function') {
    throw new Error('Machine object recipe functions must not be exposed')
  }
  RecipeBuilder.newBuilder(event, 'kubejs:builder_smoke/iron_to_gold', 'mmcece:test_missing_resource_ports', 100)
    .addItemInput('2x minecraft:iron_ingot')
    .addItemOutput('minecraft:gold_ingot')
    .setChance(0.5)
    .setTag('gold_out')
    .setParallelized(false)
    .build()

  for (const recipeId of ['builder_short_id', 'my_pack:gold', 'minecraft:builder_gold', 'mmcece:mmcece/builder_gold']) {
    RecipeBuilder.newBuilder(event, recipeId, 'mmcece:test_missing_resource_ports', 80)
      .addItemInput('minecraft:iron_ingot')
      .addItemOutput('minecraft:gold_ingot')
      .build()
  }
})
