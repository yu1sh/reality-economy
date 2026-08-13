package io.github.yu1sh.reality.economy.client;

import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import io.github.yu1sh.reality.economy.RealityEconomyMod;
import io.github.yu1sh.reality.economy.ShopDomain;
import io.github.yu1sh.reality.economy.ShopMenu;
import io.github.yu1sh.reality.economy.ShopNetwork;

/** Server-snapshot GUI for every Shop v1 command path. */
public final class ShopScreen extends AbstractContainerScreen<ShopMenu> {
    private enum Mode {
        LIST,
        DETAIL,
        EDIT_ADD,
        EDIT_CHANGE,
        ADMIN,
        RESET
    }

    private static final int GUI_WIDTH = 520;
    private static final int GUI_HEIGHT = 320;
    private static final int ENTRY_BUTTON_WIDTH = 122;
    private static final int ENTRY_BUTTON_HEIGHT = 38;

    private Mode mode = Mode.LIST;
    private ShopNetwork.ShopSnapshot snapshot;
    private String selectedEntryId = "";
    private String localMessage = "";
    private EditBox itemBox;
    private EditBox quantityBox;
    private EditBox priceBox;
    private EditBox playerBox;

    public ShopScreen(ShopMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = GUI_WIDTH;
        this.imageHeight = GUI_HEIGHT;
        this.snapshot = menu.snapshot();
    }

    @Override
    protected void init() {
        super.init();
        rebuildWidgets();
    }

    public void onServerSnapshot() {
        this.snapshot = menu.snapshot();
        if (snapshot != null && !snapshot.selectedEntryId().isBlank()) {
            selectedEntryId = snapshot.selectedEntryId();
        }
        rebuildWidgets();
    }

    @Override
    protected void rebuildWidgets() {
        clearWidgets();
        itemBox = null;
        quantityBox = null;
        priceBox = null;
        playerBox = null;

        int left = leftPos;
        int top = topPos;
        addRenderableWidget(Button.builder(Component.literal("Close"), button -> onClose())
                .bounds(left + GUI_WIDTH - 75, top + 8, 65, 20)
                .build());
        if (snapshot == null) {
            return;
        }

        switch (mode) {
            case LIST -> buildListWidgets(left, top);
            case DETAIL -> buildDetailWidgets(left, top);
            case EDIT_ADD -> buildEditorWidgets(left, top, false);
            case EDIT_CHANGE -> buildEditorWidgets(left, top, true);
            case ADMIN -> buildAdminWidgets(left, top);
            case RESET -> buildResetWidgets(left, top);
            default -> {
            }
        }
    }

    private void buildListWidgets(int left, int top) {
        int index = 0;
        for (ShopNetwork.EntryView entry : snapshot.entries()) {
            int column = index % 4;
            int row = index / 4;
            int x = left + 10 + column * 128;
            int y = top + 52 + row * 45;
            addRenderableWidget(Button.builder(
                            Component.literal(shortLabel(entry)),
                            button -> {
                                selectedEntryId = entry.id();
                                mode = Mode.DETAIL;
                                send(ShopDomain.Operation.DETAIL, entry.id(), "", 0, 0L, "", false, snapshot.page());
                            })
                    .bounds(x, y, ENTRY_BUTTON_WIDTH, ENTRY_BUTTON_HEIGHT)
                    .build());
            index++;
        }

        if (snapshot.page() > 0) {
            addRenderableWidget(Button.builder(Component.literal("Previous"), button -> {
                        mode = Mode.LIST;
                        send(ShopDomain.Operation.LIST, "", "", 0, 0L, "", false, snapshot.page() - 1);
                    })
                    .bounds(left + 10, top + GUI_HEIGHT - 34, 80, 20)
                    .build());
        }
        if ((snapshot.page() + 1) * ShopDomain.MAX_PAGE_SIZE < snapshot.totalEntries()) {
            addRenderableWidget(Button.builder(Component.literal("Next"), button -> {
                        mode = Mode.LIST;
                        send(ShopDomain.Operation.LIST, "", "", 0, 0L, "", false, snapshot.page() + 1);
                    })
                    .bounds(left + 95, top + GUI_HEIGHT - 34, 80, 20)
                    .build());
        }
        if (snapshot.clerk()) {
            addRenderableWidget(Button.builder(Component.literal("Add item"), button -> {
                        mode = Mode.EDIT_ADD;
                        localMessage = "";
                        rebuildWidgets();
                    })
                    .bounds(left + 185, top + GUI_HEIGHT - 34, 80, 20)
                    .build());
        }
        if (snapshot.administrator()) {
            addRenderableWidget(Button.builder(Component.literal("Admin"), button -> {
                        mode = Mode.ADMIN;
                        localMessage = "";
                        rebuildWidgets();
                    })
                    .bounds(left + 270, top + GUI_HEIGHT - 34, 70, 20)
                    .build());
        }
    }

    private void buildDetailWidgets(int left, int top) {
        ShopNetwork.EntryView entry = snapshot.selectedEntry();
        addRenderableWidget(Button.builder(Component.literal("Back"), button -> {
                    mode = Mode.LIST;
                    send(ShopDomain.Operation.LIST, "", "", 0, 0L, "", false, snapshot.page());
                })
                .bounds(left + 10, top + GUI_HEIGHT - 34, 70, 20)
                .build());
        if (entry == null) {
            return;
        }
        if (entry.active()) {
            addRenderableWidget(Button.builder(Component.literal("Purchase"), button ->
                            send(ShopDomain.Operation.PURCHASE, entry.id(), "", 0, 0L, "", false, snapshot.page()))
                    .bounds(left + 90, top + GUI_HEIGHT - 34, 80, 20)
                    .build());
        }
        if (snapshot.clerk()) {
            addRenderableWidget(Button.builder(Component.literal("Change"), button -> {
                        mode = Mode.EDIT_CHANGE;
                        localMessage = "";
                        rebuildWidgets();
                    })
                    .bounds(left + 180, top + GUI_HEIGHT - 34, 75, 20)
                    .build());
            if (entry.active()) {
                addRenderableWidget(Button.builder(Component.literal("Stop"), button -> {
                            mode = Mode.LIST;
                            send(ShopDomain.Operation.STOP, entry.id(), "", 0, 0L, "", false, snapshot.page());
                        })
                        .bounds(left + 260, top + GUI_HEIGHT - 34, 65, 20)
                        .build());
            } else {
                addRenderableWidget(Button.builder(Component.literal("Resume"), button -> {
                            mode = Mode.LIST;
                            send(ShopDomain.Operation.RESUME, entry.id(), "", 0, 0L, "", false, snapshot.page());
                        })
                        .bounds(left + 260, top + GUI_HEIGHT - 34, 75, 20)
                        .build());
            }
        }
    }

    private void buildEditorWidgets(int left, int top, boolean change) {
        ShopNetwork.EntryView existing = snapshot.selectedEntry();
        int y = top + 66;
        itemBox = addRenderableWidget(new EditBox(
                font, left + 170, y, 250, 20, Component.literal("item id")));
        itemBox.setMaxLength(ShopDomain.MAX_ITEM_ID_LENGTH);
        quantityBox = addRenderableWidget(new EditBox(
                font, left + 170, y + 35, 100, 20, Component.literal("quantity")));
        quantityBox.setMaxLength(3);
        priceBox = addRenderableWidget(new EditBox(
                font, left + 170, y + 70, 100, 20, Component.literal("price")));
        priceBox.setMaxLength(6);
        if (change && existing != null) {
            itemBox.setValue(existing.itemId());
            quantityBox.setValue(Integer.toString(existing.quantity()));
            priceBox.setValue(Long.toString(existing.price()));
        }
        addRenderableWidget(Button.builder(Component.literal(change ? "Apply change" : "Add entry"), button -> {
                    long price = parseLong(priceBox.getValue());
                    int quantity = parseInt(quantityBox.getValue());
                    if (price < 0L || quantity < 0 || itemBox.getValue().isBlank()) {
                        localMessage = "invalid input; server will also validate";
                        return;
                    }
                    mode = Mode.LIST;
                    send(
                            change ? ShopDomain.Operation.CHANGE : ShopDomain.Operation.ADD,
                            change && existing != null ? existing.id() : "",
                            itemBox.getValue(),
                            quantity,
                            price,
                            "",
                            false,
                            snapshot.page());
                })
                .bounds(left + 170, y + 110, 110, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> {
                    mode = change ? Mode.DETAIL : Mode.LIST;
                    rebuildWidgets();
                })
                .bounds(left + 285, y + 110, 75, 20)
                .build());
    }

    private void buildAdminWidgets(int left, int top) {
        playerBox = addRenderableWidget(new EditBox(
                font, left + 170, top + 74, 250, 20, Component.literal("online player")));
        playerBox.setMaxLength(ShopDomain.MAX_PLAYER_NAME_LENGTH);
        addRenderableWidget(Button.builder(Component.literal("Appoint clerk"), button -> {
                    send(ShopDomain.Operation.APPOINT, "", "", 0, 0L, playerBox.getValue(), false, snapshot.page());
                })
                .bounds(left + 170, top + 110, 110, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Revoke clerk"), button -> {
                    send(ShopDomain.Operation.REVOKE, "", "", 0, 0L, playerBox.getValue(), false, snapshot.page());
                })
                .bounds(left + 285, top + 110, 110, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Reset world economy"), button -> {
                    mode = Mode.RESET;
                    rebuildWidgets();
                })
                .bounds(left + 170, top + 150, 150, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Back"), button -> {
                    mode = Mode.LIST;
                    rebuildWidgets();
                })
                .bounds(left + 325, top + 150, 70, 20)
                .build());
    }

    private void buildResetWidgets(int left, int top) {
        addRenderableWidget(Button.builder(Component.literal("Confirm reset"), button -> {
                    mode = Mode.LIST;
                    send(ShopDomain.Operation.RESET, "", "", 0, 0L, "", true, snapshot.page());
                })
                .bounds(left + 170, top + 120, 110, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> {
                    mode = Mode.ADMIN;
                    rebuildWidgets();
                })
                .bounds(left + 285, top + 120, 75, 20)
                .build());
    }

    private void send(
            ShopDomain.Operation operation,
            String entryId,
            String itemId,
            int quantity,
            long price,
            String targetName,
            boolean confirmation,
            int page) {
        if (snapshot == null || Minecraft.getInstance().player == null) {
            return;
        }
        ShopNetwork.sendToServer(ShopNetwork.ShopRequest.create(
                UUID.randomUUID(),
                Minecraft.getInstance().player.getUUID(),
                snapshot.worldEpoch(),
                RealityEconomyMod.SHOP_ID,
                menu.containerId,
                snapshot.revision(),
                page,
                operation,
                safe(entryId),
                safe(itemId),
                quantity,
                price,
                safe(targetName),
                confirmation));
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos - 4, topPos - 4, leftPos + GUI_WIDTH + 4, topPos + GUI_HEIGHT + 4, 0xE0101010);
        graphics.fill(leftPos, topPos, leftPos + GUI_WIDTH, topPos + GUI_HEIGHT, 0xFF202020);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, "Reality Shop", 10, 10, 0xFFFFFF);
        if (snapshot == null) {
            graphics.drawString(font, "waiting for server snapshot", 10, 30, 0xFFAA00);
            return;
        }
        graphics.drawString(
                font,
                "epoch=" + snapshot.worldEpoch().substring(0, Math.min(8, snapshot.worldEpoch().length()))
                        + " revision=" + snapshot.revision()
                        + " page=" + (snapshot.page() + 1),
                10,
                28,
                0xB0B0B0);
        String serverMessage = snapshot.message();
        if (!serverMessage.isBlank()) {
            graphics.drawString(font, truncate(serverMessage, 72), 10, GUI_HEIGHT - 55, 0xFFFF55);
        }
        if (!localMessage.isBlank()) {
            graphics.drawString(font, truncate(localMessage, 72), 10, GUI_HEIGHT - 70, 0xFFAA00);
        }
        if (snapshot.recoveryBlocked()) {
            graphics.drawString(font, "RECOVERY REQUIRED: purchases stopped", 10, 42, 0xFF5555);
        }

        if (mode == Mode.LIST) {
            int index = 0;
            for (ShopNetwork.EntryView entry : snapshot.entries()) {
                drawItemIcon(graphics, entry.itemId(), leftPos + 14 + (index % 4) * 128,
                        topPos + 58 + (index / 4) * 45);
                index++;
            }
        } else if (mode == Mode.DETAIL && snapshot.selectedEntry() != null) {
            ShopNetwork.EntryView entry = snapshot.selectedEntry();
            graphics.drawString(font, "entry=" + entry.id(), 20, 72, 0xFFFFFF);
            graphics.drawString(font, "item=" + entry.itemId(), 20, 90, 0xFFFFFF);
            graphics.drawString(font, "quantity=" + entry.quantity(), 20, 108, 0xFFFFFF);
            graphics.drawString(font, "price=" + entry.price(), 20, 126, 0xFFFFFF);
            graphics.drawString(font, "active=" + entry.active(), 20, 144, 0xFFFFFF);
            drawItemIcon(graphics, entry.itemId(), leftPos + 350, topPos + 80);
        } else if (mode == Mode.EDIT_ADD || mode == Mode.EDIT_CHANGE) {
            graphics.drawString(font, mode == Mode.EDIT_ADD ? "Add catalog entry" : "Change catalog entry", 20, 48, 0xFFFFFF);
            graphics.drawString(font, "item id", 60, 72, 0xFFFFFF);
            graphics.drawString(font, "quantity 1..stack", 60, 107, 0xFFFFFF);
            graphics.drawString(font, "price 1..100000", 60, 142, 0xFFFFFF);
        } else if (mode == Mode.ADMIN) {
            graphics.drawString(font, "Administrator controls", 20, 52, 0xFFFFFF);
            graphics.drawString(font, "target must be another online player", 60, 64, 0xB0B0B0);
        } else if (mode == Mode.RESET) {
            graphics.drawString(font, "Reset starts a new epoch, empty ledger, and seeded catalog.", 20, 72, 0xFFFF55);
            graphics.drawString(font, "Old purchases and audit records are retained.", 20, 90, 0xFFFFFF);
        }
    }

    private void drawItemIcon(GuiGraphics graphics, String itemId, int x, int y) {
        ResourceLocation location = ResourceLocation.tryParse(itemId);
        if (location == null) {
            return;
        }
        Item item = BuiltInRegistries.ITEM.getOptional(location).orElse(null);
        if (item != null) {
            graphics.renderItem(new ItemStack(item, 1), x, y);
        }
    }

    private static String shortLabel(ShopNetwork.EntryView entry) {
        String state = entry.active() ? "" : " [stopped]";
        return truncate(entry.id() + " " + entry.quantity() + "x @" + entry.price() + state, 23);
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return -1L;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, Math.max(0, maxLength - 1)) + "…";
    }
}
