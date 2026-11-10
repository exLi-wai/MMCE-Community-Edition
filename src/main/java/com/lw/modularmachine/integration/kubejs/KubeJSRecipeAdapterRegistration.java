package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterImport;
import com.lw.modularmachine.machine.recipe.adapter.RecipeAdapterDefinitions;
import dev.latvian.mods.kubejs.event.KubeEvent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/** Declarative CRT-style adapter builders for one server-script generation. */
public final class KubeJSRecipeAdapterRegistration implements KubeEvent {
  private final Map<ResourceLocation, LinkedHashMap<String, RecipeAdapterImport>> declarations = new LinkedHashMap<>();
  private boolean open = true;
  private boolean valid = true;

  public RecipeAdapterBuilder create(String machineName, String adapterName) {
    requireOpen();
    var machine = identifier(machineName, "machine");
    var adapter = identifier(adapterName, "adapter");

    return new RecipeAdapterBuilder(this, machine, adapter, defaultImportId(adapter));
  }

  private static String defaultImportId(ResourceLocation adapter) {
    String path = adapter.getPath().replace('/', '_');
    String name = adapter.getNamespace().equals("minecraft") ? path : adapter.getNamespace() + "_" + path;
    if (name.matches("[a-zA-Z0-9_.-]{1,64}")) {
      return name;
    }
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(adapter.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private void register(ResourceLocation machine, RecipeAdapterImport declaration) {
    requireOpen();
    var imports = declarations.computeIfAbsent(machine, ignored -> new LinkedHashMap<>());
    if (imports.containsKey(declaration.id())) {
      throw reject("Duplicate recipe adapter import " + machine + "/" + declaration.id());
    }
    if (imports.size() >= RecipeAdapterDefinitions.MAX_IMPORTS) {
      throw reject("Too many recipe adapter imports");
    }
    imports.put(declaration.id(), declaration);
  }

  private ResourceLocation identifier(String value, String field) {
    var id = value == null ? null : ResourceLocation.tryParse(value);
    if (id == null) {
      throw reject("Invalid recipe adapter " + field + " ID: " + value);
    }

    return id;
  }

  private void requireOpen() {
    if (!open) {
      throw new IllegalStateException("Recipe adapter registration is closed");
    }
    if (!valid) {
      throw new IllegalStateException("Recipe adapter generation is invalid");
    }
  }

  private IllegalArgumentException reject(String reason) {
    valid = false;

    return new IllegalArgumentException(reason);
  }

  private long integer(Number value, String field, long minimum, long maximum) {
    requireOpen();
    try {
      long result = new BigDecimal(value.toString()).longValueExact();
      if (result < minimum || result > maximum) {
        throw reject("Recipe adapter " + field + " is outside its integer range");
      }
      return result;
    } catch (ArithmeticException | NumberFormatException invalid) {
      throw reject("Recipe adapter " + field + " requires an exact integer");
    } catch (NullPointerException missing) {
      throw reject("Recipe adapter " + field + " is missing");
    }
  }

  void seal() {
    open = false;
  }

  boolean valid() {
    return valid;
  }

  Map<ResourceLocation, List<RecipeAdapterImport>> snapshot() {
    if (open) {
      throw new IllegalStateException("Recipe adapter registration must be sealed before snapshot");
    }
    if (!valid) {
      throw new IllegalStateException("Recipe adapter generation is invalid");
    }
    var snapshot = new LinkedHashMap<ResourceLocation, List<RecipeAdapterImport>>();
    declarations.forEach((machine, imports) -> snapshot.put(machine, List.copyOf(imports.values())));

    return Map.copyOf(snapshot);
  }

  public static final class RecipeAdapterBuilder {
    private final KubeJSRecipeAdapterRegistration owner;
    private final ResourceLocation machine;
    private final ResourceLocation adapter;
    private String id;
    private boolean allRecipes = true;
    private List<ResourceLocation> recipes = List.of();
    private int duration;
    private long energyPerTick;
    private int priority;
    private boolean built;

    private RecipeAdapterBuilder(KubeJSRecipeAdapterRegistration owner, ResourceLocation machine, ResourceLocation adapter, String id) {
      this.owner = owner;
      this.machine = machine;
      this.adapter = adapter;
      this.id = id;
    }

    private void requireMutable() {
      owner.requireOpen();
      if (built) {
        throw owner.reject("Recipe adapter builder has already been built");
      }
    }

    public RecipeAdapterBuilder addEnergyPerTickInput(Number amount) {
      requireMutable();
      long addition = owner.integer(amount, "FE/t", 0, Long.MAX_VALUE);
      try {
        energyPerTick = Math.addExact(energyPerTick, addition);
      } catch (ArithmeticException overflow) {
        throw owner.reject("Recipe adapter total FE/t exceeds the long range");
      }

      return this;
    }

    /** Extension: overrides native duration; CRT supplied duration modifiers. */
    public RecipeAdapterBuilder setDuration(Number ticks) {
      requireMutable();
      duration = (int) owner.integer(ticks, "duration", 1, Integer.MAX_VALUE);

      return this;
    }

    /** Extension: native priority remains an explicit import setting. */
    public RecipeAdapterBuilder setPriority(Number priority) {
      requireMutable();
      this.priority = (int) owner.integer(priority, "priority", Integer.MIN_VALUE, Integer.MAX_VALUE);

      return this;
    }

    /** Extension: limits this declaration to the selected source recipe IDs. */
    public RecipeAdapterBuilder setSourceRecipes(List<String> sourceIds) {
      requireMutable();
      if (sourceIds == null) {
        throw owner.reject("Recipe adapter source ID list must not be null");
      }
      var selected = new ArrayList<ResourceLocation>(sourceIds.size());
      var seen = new HashSet<ResourceLocation>();
      for (String source : sourceIds) {
        var parsed = owner.identifier(source, "source recipe");
        if (!seen.add(parsed)) {
          throw owner.reject("Duplicate recipe adapter source " + source);
        }
        selected.add(parsed);
      }
      recipes = List.copyOf(selected);
      allRecipes = false;

      return this;
    }

    /** Extension: distinguishes multiple imports from the same adapter. */
    public RecipeAdapterBuilder setImportId(String id) {
      requireMutable();
      if (id == null || !id.matches("[a-zA-Z0-9_.-]{1,64}")) {
        throw owner.reject("Recipe adapter import ID must use 1..64 letters, digits, underscores, dots or hyphens");
      }
      this.id = id;

      return this;
    }

    public void build() {
      requireMutable();
      owner.register(
          machine,
          new RecipeAdapterImport(id, adapter, recipes, allRecipes, duration, energyPerTick, priority));
      built = true;
    }
  }
}
