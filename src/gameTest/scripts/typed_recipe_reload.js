ServerEvents.recipes(event => {
  const machine = 'mmcece:test_missing_resource_ports'
  const recipe = (id, ticks) => RecipeBuilder.newBuilder(event, id, machine, ticks)
    .addItemInput('minecraft:dirt').addItemOutput('minecraft:iron_ingot')

  recipe('mmcece_reload:edited', 20).addPreCheckHandler(ctx => {}).build()
  recipe('mmcece_reload:edited_disabled', 20).addPreCheckHandler(ctx => {}).build()
  recipe('mmcece_reload:removed', 20).addPreCheckHandler(ctx => {}).build()
  const javaReader = Java.loadClass('com.lw.modularmachine.integration.kubejs.TypedRecipeReloadGameTests')
  const duration = javaReader.addedRecipeDuration(event, 'mmcece_reload:edited')
  event.addedRecipes.forEach(entry => {
    if (String(entry.getId()) === 'mmcece_reload:edited') {
      if (duration !== 20 || !entry.hasOutput(null, 'minecraft:iron_ingot') || entry.hasOutput(null, 'minecraft:diamond')) {
        throw new Error('Expected the submitted 20-tick recipe to match only its iron ingot output')
      }
      entry.merge({duration: duration * 4})
    }
    if (String(entry.getId()) === 'mmcece_reload:edited_disabled') {
      entry.merge({'neoforge:conditions': [{type: 'neoforge:mod_loaded', modid: 'mmcece_missing_fixture'}]})
    }
    if (String(entry.getId()) === 'mmcece_reload:removed') {
      entry.remove()
    }
  })

  // The installed inline callbacks must follow the event's actual same-ID winner.
  recipe('mmcece_reload:winner', 41).addPreCheckHandler(ctx => {}).build()
  recipe('mmcece_reload:winner', 42).build()

  // A surviving native machine has priority. Explicit removal allows re-addition.
  recipe('mmcece:mmcece/test_missing_resource_energy', 99).addPreCheckHandler(ctx => {}).build()
  event.remove({id: 'mmcece:mmcece/test_fluid_row_routes'})
  recipe('mmcece:mmcece/test_fluid_row_routes', 32).build()

  recipe('minecraft:stick', 31).build()
  event.custom({
    type: 'mmcece:machine', machine: machine, duration: 33,
    inputs: [{stack: {id: 'minecraft:dirt'}, amount: 1}],
    outputs: [{stack: {id: 'minecraft:iron_ingot'}, amount: 1}],
    'neoforge:conditions': [{type: 'neoforge:mod_loaded', modid: 'mmcece_missing_fixture'}]
  }).id('mmcece_reload:disabled')
})
