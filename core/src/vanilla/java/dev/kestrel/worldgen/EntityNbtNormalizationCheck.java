package dev.kestrel.worldgen;

/** Focused regression for the structure-entity parity comparator. */
public final class EntityNbtNormalizationCheck {
    private EntityNbtNormalizationCheck() {}

    public static void main(String[] args) {
        String vanilla =
                "[{id:\"minecraft:sheep\",Pos:[1.0d,2.0d,3.0d],UUID:[I;1,2,3,4],"
                        + "attributes:["
                        + "{base:8.0d,id:\"minecraft:max_health\"},"
                        + "{base:16.0d,id:\"minecraft:follow_range\",modifiers:["
                        + "{amount:1.0d,id:\"minecraft:a\",operation:\"add_value\"},"
                        + "{amount:2.0d,id:\"minecraft:b\",operation:\"add_value\"}]}"
                        + "]}]";
        String reordered =
                "[{id:\"minecraft:sheep\",Pos:[1.0d,2.0d,3.0d],UUID:[I;9,8,7,6],"
                        + "attributes:["
                        + "{base:16.0d,id:\"minecraft:follow_range\",modifiers:["
                        + "{amount:1.0d,id:\"minecraft:a\",operation:\"add_value\"},"
                        + "{amount:2.0d,id:\"minecraft:b\",operation:\"add_value\"}]},"
                        + "{base:8.0d,id:\"minecraft:max_health\"}"
                        + "]}]";

        require(
                FeaturesTraceReplay.normalizeEntityNbt(vanilla)
                        .equals(FeaturesTraceReplay.normalizeEntityNbt(reordered)),
                "outer AttributeMap iteration order must be canonicalized");

        String moved =
                reordered.replace(
                        "Pos:[1.0d,2.0d,3.0d]",
                        "Pos:[3.0d,2.0d,1.0d]");
        require(
                !FeaturesTraceReplay.normalizeEntityNbt(vanilla)
                        .equals(FeaturesTraceReplay.normalizeEntityNbt(moved)),
                "ordered Pos list must remain exact");

        String modifiersReordered =
                reordered.replace(
                        "{amount:1.0d,id:\"minecraft:a\",operation:\"add_value\"},"
                                + "{amount:2.0d,id:\"minecraft:b\",operation:\"add_value\"}",
                        "{amount:2.0d,id:\"minecraft:b\",operation:\"add_value\"},"
                                + "{amount:1.0d,id:\"minecraft:a\",operation:\"add_value\"}");
        require(
                !FeaturesTraceReplay.normalizeEntityNbt(vanilla)
                        .equals(FeaturesTraceReplay.normalizeEntityNbt(modifiersReordered)),
                "nested modifier order must remain exact");

        String changedValue =
                reordered.replace(
                        "base:8.0d,id:\"minecraft:max_health\"",
                        "base:9.0d,id:\"minecraft:max_health\"");
        require(
                !FeaturesTraceReplay.normalizeEntityNbt(vanilla)
                        .equals(FeaturesTraceReplay.normalizeEntityNbt(changedValue)),
                "attribute values must remain exact");

        String quotedMarker =
                "[{id:\"minecraft:sheep\",CustomName:\"attributes:[literal]\","
                        + "attributes:[{base:8.0d,id:\"minecraft:max_health\"}]}]";
        require(
                FeaturesTraceReplay.normalizeEntityNbt(quotedMarker).contains(
                        "CustomName:\"attributes:[literal]\""),
                "quoted attributes marker must remain ordinary string data");

        String singleQuotedMarker =
                "[{id:\"minecraft:sheep\",CustomName:'attributes:[literal]',"
                        + "attributes:[{base:8.0d,id:'minecraft:max_health'}]}]";
        require(
                FeaturesTraceReplay.normalizeEntityNbt(singleQuotedMarker).contains(
                        "CustomName:'attributes:[literal]'"),
                "single-quoted attributes marker must remain ordinary string data");

        String secondEntity =
                "{id:\"minecraft:cat\",attributes:["
                        + "{base:10.0d,id:\"minecraft:max_health\"}]}";
        String firstEntity = vanilla.substring(1, vanilla.length() - 1);
        require(
                !FeaturesTraceReplay.normalizeEntityNbt(
                                "[" + firstEntity + "," + secondEntity + "]")
                        .equals(FeaturesTraceReplay.normalizeEntityNbt(
                                "[" + secondEntity + "," + firstEntity + "]")),
                "entity list order must remain exact");

        String nestedOnlyId =
                "[{id:\"minecraft:sheep\",attributes:["
                        + "{base:8.0d,modifiers:["
                        + "{amount:1.0d,id:\"minecraft:not_an_attribute_id\","
                        + "operation:\"add_value\"}]}]}]";
        boolean rejectedNestedOnlyId = false;
        try {
            FeaturesTraceReplay.normalizeEntityNbt(nestedOnlyId);
        } catch (IllegalArgumentException expected) {
            rejectedNestedOnlyId = true;
        }
        require(
                rejectedNestedOnlyId,
                "nested modifier id must not masquerade as top-level attribute id");

        String duplicate =
                "[{id:\"minecraft:sheep\",attributes:["
                        + "{base:8.0d,id:\"minecraft:max_health\"},"
                        + "{base:9.0d,id:\"minecraft:max_health\"}]}]";
        boolean rejectedDuplicate = false;
        try {
            FeaturesTraceReplay.normalizeEntityNbt(duplicate);
        } catch (IllegalArgumentException expected) {
            rejectedDuplicate = true;
        }
        require(rejectedDuplicate, "duplicate attribute IDs must fail closed");

        String drownedA =
                "[{id:\"minecraft:drowned\",Pos:[1.0d,2.0d,3.0d],"
                        + "attributes:["
                        + "{base:0.02d,id:\"minecraft:knockback_resistance\",modifiers:["
                        + "{amount:0.044d,id:\"minecraft:random_spawn_bonus\",operation:\"add_value\"}]},"
                        + "{base:0.092d,id:\"minecraft:spawn_reinforcements\"},"
                        + "{base:35.0d,id:\"minecraft:follow_range\",modifiers:["
                        + "{amount:0.025d,id:\"minecraft:random_spawn_bonus\",operation:\"add_multiplied_base\"}]}"
                        + "]}]";
        String drownedB =
                drownedA
                        .replace("amount:0.044d", "amount:0.011d")
                        .replace("base:0.092d,id:\"minecraft:spawn_reinforcements\"",
                                "base:0.051d,id:\"minecraft:spawn_reinforcements\"");
        require(
                FeaturesTraceReplay.normalizeEntityNbt(drownedA)
                        .equals(FeaturesTraceReplay.normalizeEntityNbt(drownedB)),
                "proven Zombie entity-local entropy fields must normalize");

        String changedFollowRange =
                drownedB.replace(
                        "amount:0.025d,id:\"minecraft:random_spawn_bonus\",operation:\"add_multiplied_base\"",
                        "amount:0.026d,id:\"minecraft:random_spawn_bonus\",operation:\"add_multiplied_base\"");
        require(
                !FeaturesTraceReplay.normalizeEntityNbt(drownedA)
                        .equals(FeaturesTraceReplay.normalizeEntityNbt(changedFollowRange)),
                "follow_range random_spawn_bonus remains strict");

        String changedKnockbackBase =
                drownedB.replace(
                        "base:0.02d,id:\"minecraft:knockback_resistance\"",
                        "base:0.03d,id:\"minecraft:knockback_resistance\"");
        require(
                !FeaturesTraceReplay.normalizeEntityNbt(drownedA)
                        .equals(FeaturesTraceReplay.normalizeEntityNbt(changedKnockbackBase)),
                "knockback base remains strict");

        String changedPosition =
                drownedB.replace("Pos:[1.0d,2.0d,3.0d]", "Pos:[1.0d,2.0d,4.0d]");
        require(
                !FeaturesTraceReplay.normalizeEntityNbt(drownedA)
                        .equals(FeaturesTraceReplay.normalizeEntityNbt(changedPosition)),
                "entity position remains strict after entropy normalization");

        String nestedIdOnly =
                "[{id:\"minecraft:sheep\",attributes:["
                        + "{base:8.0d,modifiers:["
                        + "{amount:1.0d,id:\"minecraft:not_an_attribute_id\","
                        + "operation:\"add_value\"}]}]}]";
        boolean rejectedNestedIdOnly = false;
        try {
            FeaturesTraceReplay.normalizeEntityNbt(nestedIdOnly);
        } catch (IllegalArgumentException expected) {
            rejectedNestedIdOnly = true;
        }
        require(
                rejectedNestedIdOnly,
                "nested modifier id must not masquerade as top-level attribute id");

        System.out.println("EntityNbtNormalizationCheck: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
