package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import dev.latvian.mods.kubejs.script.KubeJSContext;
import dev.latvian.mods.kubejs.script.ScriptType;
import dev.latvian.mods.rhino.Context;
import java.math.BigDecimal;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/** Internal CRT-style facade producing immutable native numeric rules. */
@ApiStatus.Internal
public final class KubeJSRecipeModifierBuilder {
  private KubeJSRecipeModifierBuilder() {
  }

  public static Builder newBuilder(Context context) {
    requireServerScript(context);
    return new Builder();
  }

  public static Builder create(Context context, String type, String io, Number value, Number operation, boolean affectChance) {
    return newBuilder(context).setRequirementType(type).setIOType(io).setValue(value).setOperation(operation)
        .isAffectChance(affectChance);
  }

  private static void requireServerScript(Context context) {
    if (!(context instanceof KubeJSContext kubeContext) || kubeContext.getType() != ScriptType.SERVER) {
      throw new IllegalStateException("Recipe modifier builders require a server script");
    }
  }

  public static final class Builder {
    private final Thread owner = Thread.currentThread();
    private RecipeModifier.Target target;
    private RecipeModifier.Io io;
    private RecipeModifier.Operation operation = RecipeModifier.Operation.ADD;
    private double value;
    private boolean affectChance;
    private boolean built;

    private Builder() {
    }

    private void requireMutable() {
      if (built || Thread.currentThread() != owner) {
        throw new IllegalStateException("Recipe modifier builder is built or used outside its creating thread");
      }
    }

    public Builder setRequirementType(String type) {
      requireMutable();
      Objects.requireNonNull(type, "requirement type");
      if (type.indexOf(':') >= 0) {
        var id = ResourceLocation.tryParse(type);
        if (id == null || !id.getNamespace().equals(ModularMachine.MODID)) {
          throw new IllegalArgumentException("Recipe modifier requirement types use mmcece or a short type name");
        }
        type = id.getPath();
      }
      target = RecipeModifier.Target.parse(type);
      return this;
    }

    public Builder setIOType(String type) {
      requireMutable();
      io = RecipeModifier.Io.parse(Objects.requireNonNull(type, "IO type"));
      return this;
    }

    public Builder setOperation(Number operation) {
      requireMutable();
      Objects.requireNonNull(operation, "operation");
      final int code;
      try {
        code = new BigDecimal(operation.toString()).intValueExact();
      } catch (ArithmeticException | NumberFormatException invalid) {
        throw new IllegalArgumentException("Recipe modifier operation requires 0 (add) or 1 (multiply)", invalid);
      }
      this.operation = switch (code) {
        case 0 -> RecipeModifier.Operation.ADD;
        case 1 -> RecipeModifier.Operation.MULTIPLY;
        default -> throw new IllegalArgumentException("Recipe modifier operation requires 0 (add) or 1 (multiply)");
      };
      return this;
    }

    public Builder setValue(Number value) {
      requireMutable();
      Objects.requireNonNull(value, "value");
      double number = value.doubleValue();
      if (!Double.isFinite(number)) {
        throw new IllegalArgumentException("Recipe modifier value must be finite");
      }
      this.value = number;
      return this;
    }

    public Builder isAffectChance(boolean enabled) {
      requireMutable();
      affectChance = enabled;
      return this;
    }

    public RecipeModifier build() {
      requireMutable();
      var result = new RecipeModifier(target, io, operation, value, affectChance);
      built = true;
      return result;
    }
  }
}
