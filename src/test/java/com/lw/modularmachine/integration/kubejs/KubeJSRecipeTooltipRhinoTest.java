package com.lw.modularmachine.integration.kubejs;

import dev.latvian.mods.rhino.CachedMethodInfo;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ContextFactory;
import dev.latvian.mods.rhino.MemberBox;
import dev.latvian.mods.rhino.NativeJavaMethod;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resolves the real exposed methods without constructing a world or recipe
 * event.
 */
class KubeJSRecipeTooltipRhinoTest {
  @Test
  void ordinaryTooltipMethodsResolveStringsVarargsAndJavascriptArrays() throws ReflectiveOperationException {
    assertTooltipMethodsResolve(KubeJSRecipeBuilder.RecipeBuilder.class);
  }

  @Test
  void adapterTooltipMethodsResolveStringsVarargsAndJavascriptArrays() throws ReflectiveOperationException {
    assertTooltipMethodsResolve(KubeJSRecipeAdapterRegistration.RecipeAdapterBuilder.class);
  }

  private static void assertTooltipMethodsResolve(Class<?> builderClass) throws ReflectiveOperationException {
    var context = new ContextFactory().enter();
    var scope = context.initStandardObjects();
    var array = context.evaluateString(scope, "['one', 'two']", "tooltip-test", 1, null);
    assertResolves(context, builderClass, "addRecipeTooltip", "one");
    assertResolves(context, builderClass, "addRecipeTooltip", "one", "two");
    assertResolves(context, builderClass, "addRecipeTooltip", array);
    assertResolves(context, builderClass, "addRecipeTooltips", array);
  }

  private static void assertResolves(Context context, Class<?> builderClass, String name, Object... arguments)
      throws ReflectiveOperationException {
    var cachedClass = context.factory.getCachedClassStorage().get(builderClass);
    var methods = Arrays.stream(builderClass.getMethods()).filter(method -> method.getName().equals(name))
        .map(method -> new NativeJavaMethod(new CachedMethodInfo(cachedClass, method), name))
        .flatMap(method -> Arrays.stream(method.methods)).toArray(MemberBox[]::new);
    var resolver = NativeJavaMethod.class
        .getDeclaredMethod("findFunction", Context.class, MemberBox[].class, Object[].class);
    resolver.setAccessible(true);

    assertTrue(
        (int) resolver.invoke(null, context, methods, arguments) >= 0,
        () -> builderClass.getSimpleName() + "." + name + " must resolve without a Rhino overload ambiguity");
  }
}
