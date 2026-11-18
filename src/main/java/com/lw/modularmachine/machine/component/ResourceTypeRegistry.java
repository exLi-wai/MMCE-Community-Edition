package com.lw.modularmachine.machine.component;

import com.lw.modularmachine.api.component.ResourceType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal startup identity registry. Registration closes before resource
 * snapshots or wire access.
 */
@ApiStatus.Internal
public final class ResourceTypeRegistry {
  private static final List<ResourceType> BUILTINS = List
      .of(ResourceType.ITEM, ResourceType.FLUID, ResourceType.ENERGY, ResourceType.CHEMICAL);
  private static final Map<ResourceLocation, ResourceType> TYPES = new LinkedHashMap<>();
  private static List<ResourceType> frozen;

  static {
    for (var type : BUILTINS) {
      TYPES.put(type.id(), type);
    }
    TYPES.put(ResourceType.ENVIRONMENT.id(), ResourceType.ENVIRONMENT);
    TYPES.put(ResourceType.LASER.id(), ResourceType.LASER);
    TYPES.put(ResourceType.EMBER.id(), ResourceType.EMBER);
    TYPES.put(ResourceType.MANA.id(), ResourceType.MANA);
    TYPES.put(ResourceType.AURA.id(), ResourceType.AURA);
    TYPES.put(ResourceType.AURA_WORLD.id(), ResourceType.AURA_WORLD);
    TYPES.put(ResourceType.WILL.id(), ResourceType.WILL);
    TYPES.put(ResourceType.VIS.id(), ResourceType.VIS);
    TYPES.put(ResourceType.FLUX.id(), ResourceType.FLUX);
    TYPES.put(ResourceType.LIFE_ESSENCE.id(), ResourceType.LIFE_ESSENCE);
    TYPES.put(ResourceType.METEOR.id(), ResourceType.METEOR);
    TYPES.put(ResourceType.ESSENTIA.id(), ResourceType.ESSENTIA);
    TYPES.put(ResourceType.STARLIGHT.id(), ResourceType.STARLIGHT);
    TYPES.put(ResourceType.CONSTELLATION.id(), ResourceType.CONSTELLATION);
    TYPES.put(ResourceType.HEAT.id(), ResourceType.HEAT);
    TYPES.put(ResourceType.COMPRESSED_AIR.id(), ResourceType.COMPRESSED_AIR);
  }

  private ResourceTypeRegistry() {
  }

  public static synchronized ResourceType register(ResourceType type) {
    if (frozen != null) {
      throw new IllegalStateException("Resource type registration is closed");
    }
    if (TYPES.size() >= 256) {
      throw new IllegalArgumentException("Too many registered resource types");
    }
    if (TYPES.putIfAbsent(type.id(), type) != null) {
      throw new IllegalArgumentException("Duplicate resource type: " + type.id());
    }
    return type;
  }

  public static synchronized List<ResourceType> snapshot() {
    if (frozen == null) {
      frozen = List.copyOf(TYPES.values());
    }
    return frozen;
  }

  /** Startup introspection does not close registration. */
  public static synchronized Optional<ResourceType> find(ResourceLocation id) {
    return Optional.ofNullable(TYPES.get(id));
  }

  /** Startup introspection does not close registration. */
  public static synchronized ResourceType require(String name) {
    for (var type : TYPES.values()) {
      if (type.name().equals(name) || type.id().toString().equals(name)) {
        return type;
      }
    }
    throw new IllegalArgumentException("Unknown resource type: " + name);
  }

  public static synchronized ResourceType requireBuiltin(int discriminator) {
    snapshot();
    if (discriminator < 0 || discriminator >= BUILTINS.size()) {
      throw new IllegalArgumentException("Unknown resource wire discriminator: " + discriminator);
    }
    return BUILTINS.get(discriminator);
  }

  public static synchronized void requireRegistered(ResourceType type) {
    snapshot();
    if (TYPES.get(type.id()) != type) {
      throw new IllegalArgumentException("Unregistered resource identity: " + type.id());
    }
  }
}
