package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterImport;
import com.lw.modularmachine.machine.recipe.adapter.RecipeAdapterDefinitions;
import com.lw.modularmachine.util.math.ExactIntegers;
import dev.latvian.mods.kubejs.event.KubeEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;

/** Declarative CRT-style adapter builders for one server-script generation. */
public final class KubeJSRecipeAdapterRegistration implements KubeEvent {
  private final Map<ResourceLocation, LinkedHashMap<String, RecipeAdapterImport>> declarations = new LinkedHashMap<>();
  private final HolderLookup.Provider registries;
  private final Thread registrationThread = Thread.currentThread();
  private boolean open = true;
  private boolean valid = true;

  KubeJSRecipeAdapterRegistration(HolderLookup.Provider registries) {
    this.registries = Objects.requireNonNull(registries);
  }

  public KubeJSRecipeAdapterBuilder create(String machineName, String adapterName) {
    requireOpen();
    var machine = identifier(machineName, "machine");
    var adapter = identifier(adapterName, "adapter");

    return new KubeJSRecipeAdapterBuilder(this, machine, adapter, defaultImportId(adapter));
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

  void register(ResourceLocation machine, RecipeAdapterImport declaration) {
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

  ResourceLocation identifier(String value, String field) {
    var id = value == null ? null : ResourceLocation.tryParse(value);
    if (id == null) {
      throw reject("Invalid recipe adapter " + field + " ID: " + value);
    }

    return id;
  }

  void requireOpen() {
    if (Thread.currentThread() != registrationThread) {
      throw new IllegalStateException("Recipe adapter registration used outside its creating thread");
    }
    if (!open) {
      throw new IllegalStateException("Recipe adapter registration is closed");
    }
    if (!valid) {
      throw new IllegalStateException("Recipe adapter generation is invalid");
    }
  }

  IllegalArgumentException reject(String reason) {
    valid = false;

    return new IllegalArgumentException(reason);
  }

  long integer(Number value, String field, long minimum, long maximum) {
    requireOpen();
    try {
      if (ExactIntegers.unsafeFloatingInteger(value)) {
        throw reject("Large recipe adapter " + field + " values require an exact decimal string");
      }
      long result = ExactIntegers.longValueExact(value);
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

  HolderLookup.Provider registries() {
    return registries;
  }

  void seal() {
    if (Thread.currentThread() != registrationThread) {
      throw new IllegalStateException("Recipe adapter registration used outside its creating thread");
    }
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

}
