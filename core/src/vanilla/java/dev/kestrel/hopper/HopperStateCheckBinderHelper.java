package dev.kestrel.hopper;

/**
 * Package-visible two-pass item-component binder shared by the hopper
 * proof fixtures ({@code HopperStateCheck} documents the why in full:
 * bare {@code Bootstrap.bootStrap()} leaves item holders component-less,
 * so any {@code new ItemStack} throws "Components not bound yet", and an
 * empty map is not sufficient because {@code getMaxStackSize} defaults to
 * 1 when the component is absent).
 *
 * <p>Extracted verbatim from {@code HopperStateCheck.bindItemComponents}
 * so both checks bind identically. The only behavioral note carried over:
 * stacksTo(1) tools/weapons stay at 64 in the fixture; both checks assert
 * only 64-max and 16-max items, so this is truthful for every assertion
 * they make.
 */
final class HopperStateCheckBinderHelper {
    private HopperStateCheckBinderHelper() {
    }

    static void bind() {
        try {
            Class<?> referenceClass = Class.forName("net.minecraft.core.Holder$Reference");
            java.lang.reflect.Method bindComponents =
                    referenceClass.getDeclaredMethod(
                            "bindComponents", Class.forName("net.minecraft.core.component.DataComponentMap"));
            bindComponents.setAccessible(true);
            Class<?> mapBuilderClass =
                    Class.forName("net.minecraft.core.component.DataComponentMap$Builder");
            java.lang.reflect.Method builderFactory =
                    Class.forName("net.minecraft.core.component.DataComponentMap")
                            .getMethod("builder");
            java.lang.reflect.Method buildMethod = mapBuilderClass.getMethod("build");
            Class<?> componentsClass = Class.forName("net.minecraft.core.component.DataComponents");
            java.lang.reflect.Field commonField = componentsClass.getField("COMMON_ITEM_COMPONENTS");
            Object common = commonField.get(null);
            java.lang.reflect.Field maxStackField = componentsClass.getField("MAX_STACK_SIZE");
            Object maxStackType = maxStackField.get(null);
            java.lang.reflect.Method setMethod = null;
            java.lang.reflect.Method addAllMethod = null;
            for (java.lang.reflect.Method m : mapBuilderClass.getMethods()) {
                if (m.getName().equals("set") && m.getParameterCount() == 2) {
                    setMethod = m;
                }
                if (m.getName().equals("addAll") && m.getParameterCount() == 1) {
                    addAllMethod = m;
                }
            }
            if (setMethod == null || addAllMethod == null) {
                throw new IllegalStateException("No Builder.set/addAll found");
            }
            Class<?> registriesClass =
                    Class.forName("net.minecraft.core.registries.BuiltInRegistries");
            Object itemRegistry = registriesClass.getField("ITEM").get(null);
            java.util.List<Object> elements = new java.util.ArrayList<>();
            java.lang.reflect.Method holders = null;
            for (java.lang.reflect.Method m : itemRegistry.getClass().getMethods()) {
                if (m.getName().equals("listElements") || m.getName().equals("holders")) {
                    holders = m;
                    break;
                }
            }
            if (holders != null) {
                Object result = holders.invoke(itemRegistry);
                if (result instanceof java.util.stream.Stream) {
                    @SuppressWarnings("unchecked")
                    java.util.List<Object> collected =
                            ((java.util.stream.Stream<Object>) result)
                                    .collect(java.util.stream.Collectors.toList());
                    elements.addAll(collected);
                } else {
                    for (Object e : (Iterable<?>) result) {
                        elements.add(e);
                    }
                }
            } else {
                java.lang.reflect.Method wrap = null;
                for (java.lang.reflect.Method m : itemRegistry.getClass().getMethods()) {
                    if (m.getName().equals("wrapAsHolder") && m.getParameterCount() == 1) {
                        wrap = m;
                        break;
                    }
                }
                if (wrap == null) {
                    throw new IllegalStateException("No holder enumeration on item registry");
                }
                for (Object item : (Iterable<?>) itemRegistry) {
                    elements.add(wrap.invoke(itemRegistry, item));
                }
            }
            Object commonMap;
            {
                Object builder = builderFactory.invoke(null);
                addAllMethod.invoke(builder, common);
                commonMap = buildMethod.invoke(builder);
            }
            for (Object holder : elements) {
                bindComponents.invoke(holder, commonMap);
            }
            java.util.Map<String, Integer> smallStacks = new java.util.HashMap<>();
            smallStacks.put("minecraft:snowball", 16);
            smallStacks.put("minecraft:egg", 16);
            smallStacks.put("minecraft:ender_pearl", 16);
            smallStacks.put("minecraft:honey_bottle", 16);
            smallStacks.put("minecraft:oak_sign", 16);
            smallStacks.put("minecraft:spruce_sign", 16);
            smallStacks.put("minecraft:birch_sign", 16);
            smallStacks.put("minecraft:jungle_sign", 16);
            smallStacks.put("minecraft:acacia_sign", 16);
            smallStacks.put("minecraft:dark_oak_sign", 16);
            smallStacks.put("minecraft:mangrove_sign", 16);
            smallStacks.put("minecraft:cherry_sign", 16);
            smallStacks.put("minecraft:pale_oak_sign", 16);
            smallStacks.put("minecraft:bamboo_sign", 16);
            smallStacks.put("minecraft:crimson_sign", 16);
            smallStacks.put("minecraft:warped_sign", 16);
            java.lang.reflect.Method unwrap = null;
            for (java.lang.reflect.Method m : Class.forName("net.minecraft.core.Holder").getMethods()) {
                if (m.getName().equals("value") && m.getParameterCount() == 0) {
                    unwrap = m;
                    break;
                }
            }
            java.lang.reflect.Method getKey = null;
            for (java.lang.reflect.Method m : itemRegistry.getClass().getMethods()) {
                if (m.getName().equals("getKey") && m.getParameterCount() == 1) {
                    getKey = m;
                    break;
                }
            }
            java.util.Map<Integer, Object> mapsBySize = new java.util.HashMap<>();
            mapsBySize.put(64, commonMap);
            int rebound = 0;
            for (Object holder : elements) {
                Object item = unwrap.invoke(holder);
                Object key = getKey.invoke(itemRegistry, item);
                Integer want = smallStacks.get(key.toString());
                if (want != null) {
                    Object map = mapsBySize.get(want);
                    if (map == null) {
                        Object builder = builderFactory.invoke(null);
                        addAllMethod.invoke(builder, common);
                        setMethod.invoke(builder, maxStackType, want);
                        map = buildMethod.invoke(builder);
                        mapsBySize.put(want, map);
                    }
                    bindComponents.invoke(holder, map);
                    rebound++;
                }
            }
            System.out.println("hopper fixture: bound components for all items "
                    + "(64 default, " + rebound + " rebound to 16).");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to bind item components", e);
        }
    }
}
