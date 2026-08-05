package ru.example.itemhotkeys;

import net.minecraft.item.ItemStack;

import java.util.Locale;
import java.util.function.Predicate;

public final class ItemNames {
    public static final Predicate<ItemStack> ENDER_TRAP =
            nameContains("Эндер Ловушка");

    public static final Predicate<ItemStack> LIVALKA =
            nameContains("Ливалка");

    public static final Predicate<ItemStack> ANTI_FLY =
            nameContains("Анти-Флай");

    /*
     * Обычная Ловушка не должна совпадать
     * с названием Эндер Ловушки.
     */
    public static final Predicate<ItemStack> TRAP =
            stack -> {
                if (stack == null || stack.isEmpty()) {
                    return false;
                }

                String name = normalize(
                        stack.getName().getString()
                );

                return name.contains(
                        normalize("Ловушка")
                ) && !name.contains(
                        normalize("Эндер Ловушка")
                );
            };

    private ItemNames() {
    }

    public static Predicate<ItemStack> nameContains(
            String expectedName
    ) {
        String expected = normalize(expectedName);

        return stack -> {
            if (stack == null || stack.isEmpty()) {
                return false;
            }

            String actual = normalize(
                    stack.getName().getString()
            );

            return actual.contains(expected);
        };
    }

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }

        return text
                .trim()
                .replace('ё', 'е')
                .replace('Ё', 'Е')
                .replace('–', '-')
                .replace('—', '-')
                .toLowerCase(Locale.ROOT);
    }
}
