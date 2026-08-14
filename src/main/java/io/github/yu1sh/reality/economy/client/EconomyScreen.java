package io.github.yu1sh.reality.economy.client;

import java.util.UUID;
import io.github.yu1sh.reality.economy.EconomyMenu;
import io.github.yu1sh.reality.economy.EconomyNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Server-snapshot Economy GUI for balance and permission-level-2 admin commands. */
public final class EconomyScreen extends AbstractContainerScreen<EconomyMenu> {
    private static final int GUI_WIDTH = 520;
    private static final int GUI_HEIGHT = 300;
    private static final int MAX_VISIBLE_PLAYER_BUTTONS = 12;

    private EconomyNetwork.EconomySnapshot snapshot;
    private EditBox targetBox;
    private EditBox amountBox;
    private EditBox reasonBox;
    private String localMessage = "";

    public EconomyScreen(EconomyMenu menu, Inventory inventory, Component title) {
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
        rebuildWidgets();
        if (snapshot != null && snapshot.hasTarget() && targetBox != null) {
            targetBox.setValue(snapshot.targetName());
        }
    }

    @Override
    protected void rebuildWidgets() {
        clearWidgets();
        targetBox = null;
        amountBox = null;
        reasonBox = null;

        int left = leftPos;
        int top = topPos;
        addRenderableWidget(Button.builder(Component.literal("Close"), button -> onClose())
                .bounds(left + GUI_WIDTH - 75, top + 8, 65, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Refresh balance"), button -> send(EconomyNetwork.Operation.BALANCE))
                .bounds(left + 20, top + 66, 120, 20)
                .build());
        if (snapshot == null || !snapshot.administrator()) {
            return;
        }

        targetBox = addRenderableWidget(new EditBox(
                font, left + 20, top + 112, 190, 20, Component.literal("online player")));
        targetBox.setMaxLength(EconomyNetwork.MAX_PLAYER_NAME_LENGTH);
        amountBox = addRenderableWidget(new EditBox(
                font, left + 20, top + 154, 190, 20, Component.literal("amount")));
        amountBox.setMaxLength(20);
        reasonBox = addRenderableWidget(new EditBox(
                font, left + 20, top + 196, 190, 20, Component.literal("single-word reason")));
        reasonBox.setMaxLength(EconomyNetwork.MAX_REASON_LENGTH);

        addRenderableWidget(Button.builder(Component.literal("Grant"), button -> send(EconomyNetwork.Operation.GRANT))
                .bounds(left + 20, top + 230, 60, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Revoke"), button -> send(EconomyNetwork.Operation.REVOKE))
                .bounds(left + 85, top + 230, 65, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Inspect"), button -> send(EconomyNetwork.Operation.INSPECT))
                .bounds(left + 155, top + 230, 65, 20)
                .build());

        int visible = Math.min(MAX_VISIBLE_PLAYER_BUTTONS, snapshot.onlinePlayers().size());
        for (int index = 0; index < visible; index++) {
            String playerName = snapshot.onlinePlayers().get(index);
            int column = index % 3;
            int row = index / 3;
            addRenderableWidget(Button.builder(Component.literal(playerName), button -> targetBox.setValue(playerName))
                    .bounds(left + 250 + column * 85, top + 82 + row * 30, 80, 20)
                    .build());
        }
    }

    private void send(EconomyNetwork.Operation operation) {
        String target = targetBox == null ? "" : targetBox.getValue();
        boolean mutation = operation == EconomyNetwork.Operation.GRANT
                || operation == EconomyNetwork.Operation.REVOKE;
        String reason = mutation && reasonBox != null ? reasonBox.getValue() : "";
        long amount = mutation ? parseLong(amountBox == null ? "0" : amountBox.getValue()) : 0L;
        if (operation != EconomyNetwork.Operation.BALANCE
                && operation != EconomyNetwork.Operation.INSPECT
                && (amount < 0L || reason.isBlank())) {
            localMessage = "server will validate amount and single-word reason";
        }
        EconomyNetwork.sendToServer(EconomyNetwork.EconomyRequest.create(
                UUID.randomUUID(),
                menu.containerId,
                operation,
                target,
                amount,
                reason));
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos - 4, topPos - 4, leftPos + GUI_WIDTH + 4, topPos + GUI_HEIGHT + 4, 0xE0101010);
        graphics.fill(leftPos, topPos, leftPos + GUI_WIDTH, topPos + GUI_HEIGHT, 0xFF202020);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, "Reality Economy", 20, 20, 0xFFFFFF);
        if (snapshot == null) {
            graphics.drawString(font, "waiting for server snapshot", 20, 42, 0xFFAA00);
            return;
        }

        graphics.drawString(font, "own balance=" + snapshot.ownBalance(), 20, 44, 0xFFFFFF);
        graphics.drawString(
                font,
                snapshot.administrator() ? "permission: level 2 (server)" : "permission: player (server)",
                190,
                44,
                0xB0B0B0);
        if (!snapshot.message().isBlank()) {
            graphics.drawString(font, truncate(snapshot.message(), 72), 20, GUI_HEIGHT - 32, snapshot.success() ? 0x55FF55 : 0xFF5555);
        }
        if (!localMessage.isBlank()) {
            graphics.drawString(font, truncate(localMessage, 72), 20, GUI_HEIGHT - 50, 0xFFAA00);
        }
        if (!snapshot.administrator()) {
            graphics.drawString(font, "Admin grant/revoke/inspect requires permission level 2.", 20, 94, 0xB0B0B0);
            return;
        }

        graphics.drawString(font, "Admin target", 20, 100, 0xFFFFFF);
        graphics.drawString(font, "amount (0..Long.MAX_VALUE)", 20, 142, 0xFFFFFF);
        graphics.drawString(font, "reason (single word)", 20, 184, 0xFFFFFF);
        graphics.drawString(font, "server-produced online players", 250, 62, 0xFFFFFF);
        if (snapshot.hasTarget()) {
            graphics.drawString(
                    font,
                    "server target=" + snapshot.targetName() + " balance=" + snapshot.targetBalance(),
                    250,
                    232,
                    0xFFFFFF);
        }
        if (snapshot.transactionId() != null) {
            graphics.drawString(
                    font,
                    "transaction=" + snapshot.transactionId(),
                    250,
                    250,
                    0xB0B0B0);
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return -1L;
        }
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, Math.max(0, maxLength - 1)) + "…";
    }
}
