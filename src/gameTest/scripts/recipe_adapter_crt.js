function addSelectorProbeFluids(builder) {
  return builder.setFluidInputTag('feed')
    .addFluidInput(Fluid.of('minecraft:water', 10))
    .addFluidInput(Fluid.of('minecraft:water', 20)).setTag('')
    .addFluidInput(Fluid.of('minecraft:lava', 30)).setTag('hot')
    .addFluidOutput(Fluid.of('minecraft:water', 40)).setTag('return')
    .addFluidOutput(Fluid.of('minecraft:water', 50)).setTriggerTime(2)
    .addFluidOutput(Fluid.of('minecraft:water', 60)).setTriggerTime(3).setTag('')
    .setFluidOutputTag('drain')
}

MMCECERecipeAdapterEvents.register(event => {
  event.create('mmcece:test_missing_resource_ports', 'minecraft:furnace')
    .setImportId('crt_naming_smoke')
    .addEnergyPerTickInput(16)
    .addEnergyPerTickInput(16)
    .setDuration(20)
    .setPriority(5)
    .setSourceRecipes(['minecraft:iron_ingot_from_smelting_iron_ore'])
    .build()

  addSelectorProbeFluids(event.create('mmcece:test_fluid_row_routes', 'minecraft:furnace'))
    .setImportId('selector_probe')
    .setSourceRecipes(['minecraft:iron_ingot_from_smelting_iron_ore'])
    .build()

  event.create('mmcece:test_missing_resource_ports', 'minecraft:furnace')
    .setImportId('not_built')
})

ServerEvents.recipes(event => {
  RecipeBuilder.newBuilder(event, 'typed_test:kjs', 'mmcece:test_missing_resource_ports', 20)
    .addEnergyPerTickInput(32)
    .addItemInput('minecraft:dirt')
    .addItemOutput('minecraft:iron_ingot')
    .build()
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

  const triggerProbe = id => RecipeBuilder.newBuilder(event, 'kubejs:builder_smoke/' + id, 'mmcece:test_missing_resource_ports', 20)
  addSelectorProbeFluids(RecipeBuilder.newBuilder(event, 'kubejs:builder_smoke/fluid_row_selectors',
    'mmcece:test_fluid_row_routes', 20)).build()
  RecipeBuilder.newBuilder(event, 'kubejs:builder_smoke/trigger_only_fluids', 'mmcece:test_fluid_row_routes', 20)
    .setFluidInputTag('feed')
    .addFluidInput(Fluid.of('minecraft:water', 15)).setTriggerTime(4)
    .setFluidOutputTag('drain')
    .addFluidOutput(Fluid.of('minecraft:lava', 25)).setTag('').setTriggerTime(9)
    .build()
  const rejectPreview = builder => {
    try {
      builder.build()
    } catch (error) {
      if (String(error).includes('Triggered item rows cannot use an item preview')) return
      throw error
    }
    throw new Error('A triggered item with a preview was accepted')
  }
  rejectPreview(triggerProbe('trigger_input_preview').addItemInput('minecraft:iron_ingot')
    .setTriggerTime(2).setPreviewStack('minecraft:diamond').addItemOutput('minecraft:gold_ingot'))
  rejectPreview(triggerProbe('trigger_output_preview').addItemInput('minecraft:iron_ingot')
    .addItemOutput('minecraft:gold_ingot').setPreViewNBT({probe: 1}).setTriggerTime(2))
  triggerProbe('zero_trigger_preview').addItemInput('minecraft:iron_ingot').addItemOutput('minecraft:gold_ingot')
    .setTriggerTime(2).setPreviewStack('minecraft:diamond').setTriggerTime(0).build()
  triggerProbe('item_argument_types')
    .addItemInput('#minecraft:planks', '2')
    .addItemInput(Ingredient.of('minecraft:iron_ingot'), 3)
    .addItemOutput(Item.of('minecraft:gold_ingot'), '9007199254740993')
    .build()

  for (const recipeId of ['builder_short_id', 'my_pack:gold', 'minecraft:builder_gold', 'mmcece:mmcece/builder_gold']) {
    RecipeBuilder.newBuilder(event, recipeId, 'mmcece:test_missing_resource_ports', 80)
      .addItemInput('minecraft:iron_ingot')
      .addItemOutput('minecraft:gold_ingot')
      .build()
  }
})
