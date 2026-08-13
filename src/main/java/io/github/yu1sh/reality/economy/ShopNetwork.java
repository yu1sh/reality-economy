package io.github.yu1sh.reality.economy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/** Versioned, server-produced Shop GUI request/snapshot contract. */
public final class ShopNetwork {
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(RealityEconomyMod.MOD_ID, "shop"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private ShopNetwork() {
    }

    static void register() {
        CHANNEL.registerMessage(
                0,
                ShopRequest.class,
                ShopRequest::encode,
                ShopRequest::decode,
                ShopRequest::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(
                1,
                ShopSnapshot.class,
                ShopSnapshot::encode,
                ShopSnapshot::decode,
                ShopSnapshot::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    static void sendTo(ServerPlayer player, ShopSnapshot snapshot) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), snapshot);
    }

    public static void sendToServer(ShopRequest request) {
        CHANNEL.sendToServer(request);
    }

    public static final class ShopRequest {
        private final int protocolVersion;
        private final UUID requestId;
        private final UUID actorId;
        private final String worldEpoch;
        private final String shopId;
        private final int menuId;
        private final long revision;
        private final int page;
        private final int operationCode;
        private final String entryId;
        private final String itemId;
        private final int quantity;
        private final long price;
        private final String targetName;
        private final boolean confirmation;

        private ShopRequest(
                int protocolVersion,
                UUID requestId,
                UUID actorId,
                String worldEpoch,
                String shopId,
                int menuId,
                long revision,
                int page,
                int operationCode,
                String entryId,
                String itemId,
                int quantity,
                long price,
                String targetName,
                boolean confirmation) {
            this.protocolVersion = protocolVersion;
            this.requestId = requestId;
            this.actorId = actorId;
            this.worldEpoch = worldEpoch;
            this.shopId = shopId;
            this.menuId = menuId;
            this.revision = revision;
            this.page = page;
            this.operationCode = operationCode;
            this.entryId = entryId;
            this.itemId = itemId;
            this.quantity = quantity;
            this.price = price;
            this.targetName = targetName;
            this.confirmation = confirmation;
        }

        public static ShopRequest create(
                UUID requestId,
                UUID actorId,
                String worldEpoch,
                String shopId,
                int menuId,
                long revision,
                int page,
                ShopDomain.Operation operation,
                String entryId,
                String itemId,
                int quantity,
                long price,
                String targetName,
                boolean confirmation) {
            return new ShopRequest(
                    ShopDomain.PROTOCOL_VERSION,
                    requestId,
                    actorId,
                    worldEpoch,
                    shopId,
                    menuId,
                    revision,
                    page,
                    operation == null ? -1 : operation.code(),
                    entryId,
                    itemId,
                    quantity,
                    price,
                    targetName,
                    confirmation);
        }

        int protocolVersion() {
            return protocolVersion;
        }

        UUID requestId() {
            return requestId;
        }

        UUID actorId() {
            return actorId;
        }

        String worldEpoch() {
            return worldEpoch;
        }

        String shopId() {
            return shopId;
        }

        int menuId() {
            return menuId;
        }

        long revision() {
            return revision;
        }

        int page() {
            return page;
        }

        int operationCode() {
            return operationCode;
        }

        String entryId() {
            return entryId;
        }

        String itemId() {
            return itemId;
        }

        int quantity() {
            return quantity;
        }

        long price() {
            return price;
        }

        String targetName() {
            return targetName;
        }

        boolean confirmation() {
            return confirmation;
        }

        private static void encode(ShopRequest request, FriendlyByteBuf buffer) {
            buffer.writeVarInt(request.protocolVersion);
            buffer.writeUUID(request.requestId);
            buffer.writeUUID(request.actorId);
            buffer.writeUtf(request.worldEpoch, 64);
            buffer.writeUtf(request.shopId, 32);
            buffer.writeVarInt(request.menuId);
            buffer.writeVarLong(request.revision);
            buffer.writeVarInt(request.page);
            buffer.writeVarInt(request.operationCode);
            buffer.writeUtf(request.entryId, ShopDomain.MAX_ID_LENGTH);
            buffer.writeUtf(request.itemId, ShopDomain.MAX_ITEM_ID_LENGTH);
            buffer.writeVarInt(request.quantity);
            buffer.writeVarLong(request.price);
            buffer.writeUtf(request.targetName, ShopDomain.MAX_PLAYER_NAME_LENGTH);
            buffer.writeBoolean(request.confirmation);
        }

        private static ShopRequest decode(FriendlyByteBuf buffer) {
            return new ShopRequest(
                    buffer.readVarInt(),
                    buffer.readUUID(),
                    buffer.readUUID(),
                    buffer.readUtf(64),
                    buffer.readUtf(32),
                    buffer.readVarInt(),
                    buffer.readVarLong(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readUtf(ShopDomain.MAX_ID_LENGTH),
                    buffer.readUtf(ShopDomain.MAX_ITEM_ID_LENGTH),
                    buffer.readVarInt(),
                    buffer.readVarLong(),
                    buffer.readUtf(ShopDomain.MAX_PLAYER_NAME_LENGTH),
                    buffer.readBoolean());
        }

        private static void handle(ShopRequest request, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer sender = context.getSender();
                if (sender != null) {
                    ShopService.handlePacket(sender, request);
                }
            });
            context.setPacketHandled(true);
        }
    }

    public static final class ShopSnapshot {
        private final int protocolVersion;
        private final int menuId;
        private final String worldEpoch;
        private final String shopId;
        private final long revision;
        private final boolean clerk;
        private final boolean administrator;
        private final boolean recoveryBlocked;
        private final int page;
        private final int totalEntries;
        private final String selectedEntryId;
        private final EntryView selectedEntry;
        private final List<EntryView> entries;
        private final String message;

        ShopSnapshot(
                int protocolVersion,
                int menuId,
                String worldEpoch,
                String shopId,
                long revision,
                boolean clerk,
                boolean administrator,
                boolean recoveryBlocked,
                int page,
                int totalEntries,
                String selectedEntryId,
                EntryView selectedEntry,
                List<EntryView> entries,
                String message) {
            this.protocolVersion = protocolVersion;
            this.menuId = menuId;
            this.worldEpoch = worldEpoch;
            this.shopId = shopId;
            this.revision = revision;
            this.clerk = clerk;
            this.administrator = administrator;
            this.recoveryBlocked = recoveryBlocked;
            this.page = page;
            this.totalEntries = totalEntries;
            this.selectedEntryId = selectedEntryId;
            this.selectedEntry = selectedEntry;
            this.entries = List.copyOf(entries);
            this.message = message;
        }

        public int menuId() {
            return menuId;
        }

        public String worldEpoch() {
            return worldEpoch;
        }

        public String shopId() {
            return shopId;
        }

        public long revision() {
            return revision;
        }

        public boolean clerk() {
            return clerk;
        }

        public boolean administrator() {
            return administrator;
        }

        public boolean recoveryBlocked() {
            return recoveryBlocked;
        }

        public int page() {
            return page;
        }

        public int totalEntries() {
            return totalEntries;
        }

        public String selectedEntryId() {
            return selectedEntryId;
        }

        public EntryView selectedEntry() {
            return selectedEntry;
        }

        public List<EntryView> entries() {
            return entries;
        }

        public String message() {
            return message;
        }

        private static void encode(ShopSnapshot snapshot, FriendlyByteBuf buffer) {
            buffer.writeVarInt(snapshot.protocolVersion);
            buffer.writeVarInt(snapshot.menuId);
            buffer.writeUtf(snapshot.worldEpoch, 64);
            buffer.writeUtf(snapshot.shopId, 32);
            buffer.writeVarLong(snapshot.revision);
            buffer.writeBoolean(snapshot.clerk);
            buffer.writeBoolean(snapshot.administrator);
            buffer.writeBoolean(snapshot.recoveryBlocked);
            buffer.writeVarInt(snapshot.page);
            buffer.writeVarInt(snapshot.totalEntries);
            buffer.writeBoolean(snapshot.selectedEntry != null);
            if (snapshot.selectedEntry != null) {
                writeEntry(snapshot.selectedEntry, buffer);
            }
            buffer.writeVarInt(snapshot.entries.size());
            for (EntryView entry : snapshot.entries) {
                writeEntry(entry, buffer);
            }
            buffer.writeUtf(snapshot.message, 256);
        }

        private static ShopSnapshot decode(FriendlyByteBuf buffer) {
            int protocolVersion = buffer.readVarInt();
            int menuId = buffer.readVarInt();
            String worldEpoch = buffer.readUtf(64);
            String shopId = buffer.readUtf(32);
            long revision = buffer.readVarLong();
            boolean clerk = buffer.readBoolean();
            boolean administrator = buffer.readBoolean();
            boolean recoveryBlocked = buffer.readBoolean();
            int page = buffer.readVarInt();
            int totalEntries = buffer.readVarInt();
            EntryView selectedEntry = buffer.readBoolean() ? readEntry(buffer) : null;
            int entryCount = buffer.readVarInt();
            if (entryCount < 0 || entryCount > ShopDomain.MAX_PAGE_SIZE) {
                throw new IllegalArgumentException("invalid Shop snapshot entry count");
            }
            List<EntryView> entries = new java.util.ArrayList<>();
            for (int index = 0; index < entryCount; index++) {
                entries.add(readEntry(buffer));
            }
            String message = buffer.readUtf(256);
            return new ShopSnapshot(
                    protocolVersion,
                    menuId,
                    worldEpoch,
                    shopId,
                    revision,
                    clerk,
                    administrator,
                    recoveryBlocked,
                    page,
                    totalEntries,
                    selectedEntry == null ? "" : selectedEntry.id(),
                    selectedEntry,
                    entries,
                    message);
        }

        private static void writeEntry(EntryView entry, FriendlyByteBuf buffer) {
            buffer.writeUtf(entry.id(), ShopDomain.MAX_ID_LENGTH);
            buffer.writeUtf(entry.itemId(), ShopDomain.MAX_ITEM_ID_LENGTH);
            buffer.writeVarInt(entry.quantity());
            buffer.writeVarLong(entry.price());
            buffer.writeBoolean(entry.active());
        }

        private static EntryView readEntry(FriendlyByteBuf buffer) {
            return new EntryView(
                    buffer.readUtf(ShopDomain.MAX_ID_LENGTH),
                    buffer.readUtf(ShopDomain.MAX_ITEM_ID_LENGTH),
                    buffer.readVarInt(),
                    buffer.readVarLong(),
                    buffer.readBoolean());
        }

        private static void handle(ShopSnapshot snapshot, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> () -> io.github.yu1sh.reality.economy.client.ShopClient.receiveSnapshot(snapshot)));
            context.setPacketHandled(true);
        }
    }

    public record EntryView(String id, String itemId, int quantity, long price, boolean active) {
    }
}
