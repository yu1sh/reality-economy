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
import org.lwjgl.glfw.GLFW;
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
    private int itemPickerPage;
    private int itemChoiceIndex = -1;
    private String draftEntryId = "";
    private String draftItemId = "";
    private String draftQuantity = "";
    private String draftPrice = "";
    private EditBox itemBox;
    private EditBox quantityBox;
    private EditBox priceBox;
    private EditBox playerBox;
    private EditBox purchaseBox;

    public ShopScreen(ShopMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = GUI_WIDTH;
        this.imageHeight = GUI_HEIGHT;
        this.snapshot = menu.snapshot();
        if (snapshot != null) {
            itemPickerPage = snapshot.itemPage();
        }
    }

    @Override
    protected void init() {
        super.init();
        rebuildWidgets();
    }

    public void onServerSnapshot() {
        rememberDraft();
        this.snapshot = menu.snapshot();
        if (snapshot != null) {
            itemPickerPage = snapshot.itemPage();
            if (!snapshot.selectedEntryId().isBlank()) {
                selectedEntryId = snapshot.selectedEntryId();
            }
            if (isEditorMode() && !snapshot.clerk()) {
                mode = Mode.LIST;
                clearDraft();
            }
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
        purchaseBox = null;

        int left = leftPos;
        int top = topPos;
        addRenderableWidget(Button.builder(text("shop.close"), button -> onClose())
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
            addRenderableWidget(Button.builder(text("shop.previous"), button -> {
                        mode = Mode.LIST;
                        send(ShopDomain.Operation.LIST, "", "", 0, 0L, "", false, snapshot.page() - 1);
                    })
                    .bounds(left + 10, top + GUI_HEIGHT - 34, 80, 20)
                    .build());
        }
        if ((snapshot.page() + 1) * ShopDomain.MAX_PAGE_SIZE < snapshot.totalEntries()) {
            addRenderableWidget(Button.builder(text("shop.next"), button -> {
                        mode = Mode.LIST;
                        send(ShopDomain.Operation.LIST, "", "", 0, 0L, "", false, snapshot.page() + 1);
                    })
                    .bounds(left + 95, top + GUI_HEIGHT - 34, 80, 20)
                    .build());
        }
        if (snapshot.clerk()) {
            addRenderableWidget(Button.builder(text("shop.add_item"), button -> {
                        mode = Mode.EDIT_ADD;
                        localMessage = "";
                        clearDraft();
                        rebuildWidgets();
                    })
                    .bounds(left + 185, top + GUI_HEIGHT - 34, 80, 20)
                    .build());
        }
        if (snapshot.administrator()) {
            addRenderableWidget(Button.builder(text("shop.admin"), button -> {
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
        addRenderableWidget(Button.builder(text("shop.back"), button -> goBack())
                .bounds(left + 10, top + GUI_HEIGHT - 34, 70, 20)
                .build());
        if (entry == null) {
            return;
        }
        if (entry.active()) {
            addRenderableWidget(Button.builder(text("shop.purchase"), button ->
                            send(ShopDomain.Operation.PURCHASE, entry.id(), "", 0, 0L, "", false, snapshot.page()))
                    .bounds(left + 90, top + GUI_HEIGHT - 34, 80, 20)
                    .build());
        }
        if (snapshot.clerk()) {
            addRenderableWidget(Button.builder(text("shop.change"), button -> {
                        mode = Mode.EDIT_CHANGE;
                        localMessage = "";
                        draftEntryId = entry.id();
                        draftItemId = entry.itemId();
                        draftQuantity = Integer.toString(entry.quantity());
                        draftPrice = Long.toString(entry.price());
                        rebuildWidgets();
                    })
                    .bounds(left + 180, top + GUI_HEIGHT - 34, 75, 20)
                    .build());
            if (entry.active()) {
                addRenderableWidget(Button.builder(text("shop.stop"), button -> {
                            mode = Mode.LIST;
                            send(ShopDomain.Operation.STOP, entry.id(), "", 0, 0L, "", false, snapshot.page());
                        })
                        .bounds(left + 260, top + GUI_HEIGHT - 34, 65, 20)
                        .build());
            } else {
                addRenderableWidget(Button.builder(text("shop.resume"), button -> {
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
                font, left + 170, y, 250, 20, text("shop.item_id")));
        itemBox.setMaxLength(ShopDomain.MAX_ITEM_ID_LENGTH);
        quantityBox = addRenderableWidget(new EditBox(
                font, left + 170, y + 35, 100, 20, text("shop.quantity")));
        quantityBox.setMaxLength(3);
        priceBox = addRenderableWidget(new EditBox(
                font, left + 170, y + 70, 100, 20, text("shop.price")));
        priceBox.setMaxLength(6);
        if (change && existing != null && !existing.id().equals(draftEntryId)) {
            draftEntryId = existing.id();
            draftItemId = existing.itemId();
            draftQuantity = Integer.toString(existing.quantity());
            draftPrice = Long.toString(existing.price());
        }
        itemBox.setValue(draftItemId);
        quantityBox.setValue(draftQuantity);
        priceBox.setValue(draftPrice);
        addRenderableWidget(Button.builder(text(change ? "shop.apply_change" : "shop.add_entry"), button -> {
                    long price = parseLong(priceBox.getValue());
                    int quantity = parseInt(quantityBox.getValue());
                    if (price < 0L || quantity < 0 || itemBox.getValue().isBlank()) {
                        localMessage = text("shop.invalid_input").getString();
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
        addRenderableWidget(Button.builder(text("shop.cancel"), button -> {
                    clearDraft();
                    mode = change ? Mode.DETAIL : Mode.LIST;
                    rebuildWidgets();
                })
                .bounds(left + 285, y + 110, 75, 20)
                .build());
        buildItemPickerWidgets(left, top, y + 110);
    }

    private void buildAdminWidgets(int left, int top) {
        playerBox = addRenderableWidget(new EditBox(
                font, left + 170, top + 74, 250, 20, text("shop.online_player")));
        playerBox.setMaxLength(ShopDomain.MAX_PLAYER_NAME_LENGTH);
        addRenderableWidget(Button.builder(text("shop.appoint_clerk"), button -> {
                    send(ShopDomain.Operation.APPOINT, "", "", 0, 0L, playerBox.getValue(), false, snapshot.page());
                })
                .bounds(left + 170, top + 110, 110, 20)
                .build());
        addRenderableWidget(Button.builder(text("shop.revoke_clerk"), button -> {
                    send(ShopDomain.Operation.REVOKE, "", "", 0, 0L, playerBox.getValue(), false, snapshot.page());
                })
                .bounds(left + 285, top + 110, 110, 20)
                .build());
        addRenderableWidget(Button.builder(text("shop.reset_world_economy"), button -> {
                    mode = Mode.RESET;
                    rebuildWidgets();
                })
                .bounds(left + 170, top + 150, 150, 20)
                .build());
        addRenderableWidget(Button.builder(text("shop.back"), button -> goBack())
                .bounds(left + 325, top + 150, 70, 20)
                .build());
        purchaseBox = addRenderableWidget(new EditBox(
                font, left + 170, top + 184, 250, 20, text("shop.purchase_id")));
        purchaseBox.setMaxLength(64);
        addRenderableWidget(Button.builder(text("shop.refresh_recovery"), button -> {
                    send(ShopDomain.Operation.RECOVERY_STATUS, "", "", 0, 0L, "", false, snapshot.page());
                })
                .bounds(left + 10, top + 184, 145, 20)
                .build());
        addRenderableWidget(Button.builder(text("shop.retry_debit"), button -> {
                    send(ShopDomain.Operation.RECOVERY_RETRY,
                            purchaseBox.getValue(), "", 0, 0L, "", false, snapshot.page());
                })
                .bounds(left + 170, top + 220, 110, 20)
                .build());
        addRenderableWidget(Button.builder(text("shop.resolve_uncertain"), button -> {
                    send(ShopDomain.Operation.RECOVERY_RESOLVE,
                            purchaseBox.getValue(), "", 0, 0L, "", false, snapshot.page());
                })
                .bounds(left + 285, top + 220, 135, 20)
                .build());
        int selectionLimit = Math.min(3, snapshot.recoveries().size());
        for (int index = 0; index < selectionLimit; index++) {
            ShopNetwork.RecoveryView recovery = snapshot.recoveries().get(index);
            int row = index;
            addRenderableWidget(Button.builder(
                            text("shop.use_purchase", truncate(recovery.purchaseId(), 10)),
                            button -> purchaseBox.setValue(recovery.purchaseId()))
                    .bounds(left + 10 + (index % 2) * 80, top + 250 + (index / 2) * 22, 75, 20)
                    .build());
        }
    }

    private void buildResetWidgets(int left, int top) {
        addRenderableWidget(Button.builder(text("shop.confirm_reset"), button -> {
                    mode = Mode.LIST;
                    send(ShopDomain.Operation.RESET, "", "", 0, 0L, "", true, snapshot.page());
                })
                .bounds(left + 170, top + 120, 110, 20)
                .build());
        addRenderableWidget(Button.builder(text("shop.cancel"), button -> {
                    mode = Mode.ADMIN;
                    rebuildWidgets();
                })
                .bounds(left + 285, top + 120, 75, 20)
                .build());
    }

    private void buildItemPickerWidgets(int left, int top, int navigationY) {
        if (snapshot == null || !snapshot.clerk()) {
            return;
        }
        int pickerTop = top + 66;
        for (int index = 0; index < snapshot.itemChoices().size(); index++) {
            ShopNetwork.ItemView itemChoice = snapshot.itemChoices().get(index);
            int column = index % 2;
            int row = index / 2;
            addRenderableWidget(Button.builder(
                            text("shop.item_choice", shortItemLabel(itemChoice.itemId())),
                            button -> selectItem(itemChoice.itemId()))
                    .bounds(left + 10 + column * 75, pickerTop + row * 24, 70, 20)
                    .build());
        }
        if (snapshot.itemPage() > 0) {
            addRenderableWidget(Button.builder(text("shop.previous"), button -> changeItemPage(-1))
                    .bounds(left + 10, navigationY, 80, 20)
                    .build());
        }
        if ((snapshot.itemPage() + 1) * ShopDomain.ITEM_PICKER_PAGE_SIZE
                < snapshot.totalItemChoices()) {
            addRenderableWidget(Button.builder(text("shop.next"), button -> changeItemPage(1))
                    .bounds(left + 95, navigationY, 80, 20)
                    .build());
        }
    }

    private void selectItem(String itemId) {
        draftItemId = itemId;
        if (itemBox != null) {
            itemBox.setValue(itemId);
        }
        if (snapshot != null) {
            for (int index = 0; index < snapshot.itemChoices().size(); index++) {
                if (snapshot.itemChoices().get(index).itemId().equals(itemId)) {
                    itemChoiceIndex = index;
                    break;
                }
            }
        }
    }

    private void changeItemPage(int delta) {
        if (snapshot == null || !snapshot.clerk() || snapshot.totalItemChoices() == 0) {
            return;
        }
        int maxPage = (snapshot.totalItemChoices() - 1) / ShopDomain.ITEM_PICKER_PAGE_SIZE;
        int nextPage = Math.max(0, Math.min(snapshot.itemPage() + delta, maxPage));
        if (nextPage == snapshot.itemPage()) {
            return;
        }
        rememberDraft();
        itemPickerPage = nextPage;
        itemChoiceIndex = -1;
        send(ShopDomain.Operation.LIST, "", "", 0, 0L, "", false, snapshot.page());
    }

    private void moveItemChoice(int delta) {
        if (snapshot == null || snapshot.itemChoices().isEmpty()) {
            return;
        }
        int nextIndex = itemChoiceIndex < 0 ? 0 : itemChoiceIndex + delta;
        nextIndex = Math.max(0, Math.min(nextIndex, snapshot.itemChoices().size() - 1));
        itemChoiceIndex = nextIndex;
        selectItem(snapshot.itemChoices().get(nextIndex).itemId());
    }

    private void rememberDraft() {
        if (!isEditorMode()) {
            return;
        }
        if (itemBox != null) {
            draftItemId = itemBox.getValue();
        }
        if (quantityBox != null) {
            draftQuantity = quantityBox.getValue();
        }
        if (priceBox != null) {
            draftPrice = priceBox.getValue();
        }
    }

    private void clearDraft() {
        draftEntryId = "";
        draftItemId = "";
        draftQuantity = "";
        draftPrice = "";
        itemChoiceIndex = -1;
    }

    private void goBack() {
        switch (mode) {
            case DETAIL -> {
                mode = Mode.LIST;
                send(ShopDomain.Operation.LIST, "", "", 0, 0L, "", false, snapshot.page());
            }
            case EDIT_ADD -> {
                clearDraft();
                mode = Mode.LIST;
                rebuildWidgets();
            }
            case EDIT_CHANGE -> {
                clearDraft();
                mode = Mode.DETAIL;
                rebuildWidgets();
            }
            case ADMIN -> {
                mode = Mode.LIST;
                rebuildWidgets();
            }
            case RESET -> {
                mode = Mode.ADMIN;
                rebuildWidgets();
            }
            case LIST -> onClose();
            default -> {
            }
        }
    }

    private boolean isEditorMode() {
        return mode == Mode.EDIT_ADD || mode == Mode.EDIT_CHANGE;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && mode != Mode.LIST) {
            goBack();
            return true;
        }
        if (isEditorMode() && snapshot != null && snapshot.clerk()
                && !(getFocused() instanceof EditBox)) {
            if (keyCode == GLFW.GLFW_KEY_PAGE_UP) {
                changeItemPage(-1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
                changeItemPage(1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_UP) {
                moveItemChoice(-1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_RIGHT || keyCode == GLFW.GLFW_KEY_DOWN) {
                moveItemChoice(1);
                return true;
            }
            if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                    && getFocused() == null) {
                moveItemChoice(0);
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
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
        rememberDraft();
        ShopNetwork.sendToServer(ShopNetwork.ShopRequest.create(
                UUID.randomUUID(),
                Minecraft.getInstance().player.getUUID(),
                snapshot.worldEpoch(),
                RealityEconomyMod.SHOP_ID,
                menu.containerId,
                snapshot.revision(),
                page,
                itemPickerPage,
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
        graphics.drawString(font, text("shop.title"), 10, 10, 0xFFFFFF);
        if (snapshot == null) {
            graphics.drawString(font, text("shop.waiting"), 10, 30, 0xFFAA00);
            return;
        }
        graphics.drawString(
                font,
                text("shop.status",
                        snapshot.worldEpoch().substring(0, Math.min(8, snapshot.worldEpoch().length())),
                        snapshot.revision(),
                        snapshot.page() + 1),
                10,
                28,
                0xB0B0B0);
        String serverMessage = snapshot.message();
        if (!serverMessage.isBlank()) {
            graphics.drawString(font, text("shop.server_message", truncate(serverMessage, 72)),
                    10, GUI_HEIGHT - 55, 0xFFFF55);
        }
        if (!localMessage.isBlank()) {
            graphics.drawString(font, text("shop.local_message", truncate(localMessage, 72)),
                    10, GUI_HEIGHT - 70, 0xFFAA00);
        }
        if (snapshot.recoveryBlocked()) {
            graphics.drawString(font, text("shop.recovery_required"), 10, 42, 0xFF5555);
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
            graphics.drawString(font, text("shop.entry", entry.id()), 20, 72, 0xFFFFFF);
            graphics.drawString(font, text("shop.item", entry.itemId()), 20, 90, 0xFFFFFF);
            graphics.drawString(font, text("shop.quantity_value", entry.quantity()), 20, 108, 0xFFFFFF);
            graphics.drawString(font, text("shop.price_value", entry.price()), 20, 126, 0xFFFFFF);
            graphics.drawString(font, text("shop.active", entry.active()), 20, 144, 0xFFFFFF);
            drawItemIcon(graphics, entry.itemId(), leftPos + 350, topPos + 80);
        } else if (mode == Mode.EDIT_ADD || mode == Mode.EDIT_CHANGE) {
            graphics.drawString(font,
                    text(mode == Mode.EDIT_ADD ? "shop.add_catalog_entry" : "shop.change_catalog_entry"),
                    20, 48, 0xFFFFFF);
            graphics.drawString(font, text("shop.item_picker"), 10, 58, 0xB0B0B0);
            graphics.drawString(font, text("shop.item_id"), 170, 58, 0xFFFFFF);
            graphics.drawString(font, text("shop.quantity_hint"), 170, 93, 0xFFFFFF);
            graphics.drawString(font, text("shop.price_hint"), 170, 128, 0xFFFFFF);
            if (snapshot.clerk() && snapshot.totalItemChoices() > 0) {
                int totalPages = (snapshot.totalItemChoices() - 1) / ShopDomain.ITEM_PICKER_PAGE_SIZE + 1;
                graphics.drawString(font, text("shop.item_picker_page", snapshot.itemPage() + 1, totalPages),
                        10, 168, 0xB0B0B0);
                for (int index = 0; index < snapshot.itemChoices().size(); index++) {
                    ShopNetwork.ItemView itemChoice = snapshot.itemChoices().get(index);
                    drawItemIcon(graphics, itemChoice.itemId(),
                            leftPos + 12 + (index % 2) * 75,
                            topPos + 68 + (index / 2) * 24);
                }
            }
        } else if (mode == Mode.ADMIN) {
            graphics.drawString(font, text("shop.administrator_controls"), 20, 52, 0xFFFFFF);
            graphics.drawString(font, text("shop.target_another_online"), 60, 64, 0xB0B0B0);
            graphics.drawString(font, text("shop.recovery_records", snapshot.recoveries().size()), 20, 174, 0xFFFFFF);
            int recoveryIndex = 0;
            for (ShopNetwork.RecoveryView recovery : snapshot.recoveries()) {
                if (recoveryIndex >= 3) {
                    break;
                }
                graphics.drawString(
                        font,
                        text("shop.recovery_line", truncate(recovery.purchaseId(), 10), recovery.status(),
                                recovery.deliveryConfirmed(), recovery.debitRecorded()),
                        20,
                        188 + recoveryIndex * 18,
                        recovery.deliveryConfirmed() ? 0xFFFF55 : 0xFFAA55);
                recoveryIndex++;
            }
        } else if (mode == Mode.RESET) {
            graphics.drawString(font, text("shop.reset_warning"), 20, 72, 0xFFFF55);
            graphics.drawString(font, text("shop.reset_retained"), 20, 90, 0xFFFFFF);
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

    private static String shortItemLabel(String itemId) {
        ResourceLocation location = ResourceLocation.tryParse(itemId);
        return truncate(location == null ? itemId : location.getPath(), 11);
    }

    private static Component text(String key, Object... arguments) {
        return Component.translatable("reality_economy." + key, arguments);
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
