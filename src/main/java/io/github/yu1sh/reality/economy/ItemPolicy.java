package io.github.yu1sh.reality.economy;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Server-only allowlist for ordinary, tag-free vanilla shop items. */
final class ItemPolicy {
    private static final List<String> SAFE_VANILLA_ITEMS = List.of(
            "minecraft:apple",
            "minecraft:baked_potato",
            "minecraft:beetroot",
            "minecraft:bread",
            "minecraft:carrot",
            "minecraft:charcoal",
            "minecraft:cobblestone",
            "minecraft:coal",
            "minecraft:coarse_dirt",
            "minecraft:cooked_beef",
            "minecraft:cooked_chicken",
            "minecraft:cooked_cod",
            "minecraft:cooked_mutton",
            "minecraft:cooked_porkchop",
            "minecraft:cooked_rabbit",
            "minecraft:cooked_salmon",
            "minecraft:cracked_stone_bricks",
            "minecraft:dark_oak_log",
            "minecraft:dark_oak_planks",
            "minecraft:dirt",
            "minecraft:dried_kelp",
            "minecraft:end_stone",
            "minecraft:glass",
            "minecraft:glass_pane",
            "minecraft:glow_berries",
            "minecraft:glowstone",
            "minecraft:gravel",
            "minecraft:iron_ingot",
            "minecraft:jungle_log",
            "minecraft:jungle_planks",
            "minecraft:lantern",
            "minecraft:melon_slice",
            "minecraft:mossy_cobblestone",
            "minecraft:mossy_stone_bricks",
            "minecraft:oak_log",
            "minecraft:oak_planks",
            "minecraft:obsidian",
            "minecraft:potato",
            "minecraft:red_sand",
            "minecraft:redstone_torch",
            "minecraft:sand",
            "minecraft:sea_lantern",
            "minecraft:spruce_log",
            "minecraft:spruce_planks",
            "minecraft:stick",
            "minecraft:stone",
            "minecraft:stone_bricks",
            "minecraft:sugar_cane",
            "minecraft:sweet_berries",
            "minecraft:torch",
            "minecraft:wheat",
            "minecraft:wheat_seeds",
            "minecraft:wooden_button",
            "minecraft:wooden_pressure_plate");

    private ItemPolicy() {
    }

    static Item resolve(String itemId) {
        if (itemId == null || itemId.length() > ShopDomain.MAX_ITEM_ID_LENGTH
                || !SAFE_VANILLA_ITEMS.contains(itemId)) {
            return null;
        }

        ResourceLocation location = ResourceLocation.tryParse(itemId);
        if (location == null || !"minecraft".equals(location.getNamespace())) {
            return null;
        }

        Item item = BuiltInRegistries.ITEM.getOptional(location).orElse(null);
        if (item == null || item == BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace("air"))) {
            return null;
        }

        ItemStack cleanStack = new ItemStack(item, 1);
        if (cleanStack.hasTag() || item.getMaxStackSize() <= 0) {
            return null;
        }
        return item;
    }

    /**
     * Returns the fixed-order subset of the existing allowlist that currently
     * resolves to a clean, stackable vanilla item in the server registry.
     */
    static List<String> pickerItemIds() {
        List<String> resolved = new ArrayList<>();
        for (String itemId : SAFE_VANILLA_ITEMS) {
            if (resolve(itemId) != null) {
                resolved.add(itemId);
            }
        }
        return List.copyOf(resolved);
    }

    static boolean validQuantity(Item item, int quantity) {
        return item != null && quantity >= 1 && quantity <= ShopDomain.MAX_QUANTITY
                && quantity <= item.getMaxStackSize();
    }

    static boolean validPrice(long price) {
        return price >= ShopDomain.MIN_PRICE && price <= ShopDomain.MAX_PRICE;
    }
}
