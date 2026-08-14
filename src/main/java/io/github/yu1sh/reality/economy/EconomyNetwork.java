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

/** Versioned Economy GUI request/snapshot contract. */
public final class EconomyNetwork {
    public static final int PROTOCOL_VERSION = 1;
    public static final int MAX_PLAYER_NAME_LENGTH = 64;
    public static final int MAX_REASON_LENGTH = 256;
    public static final int MAX_MESSAGE_LENGTH = 256;
    public static final int MAX_ONLINE_PLAYERS = 128;
    private static final String PROTOCOL = Integer.toString(PROTOCOL_VERSION);

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(RealityEconomyMod.MOD_ID, "economy"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals);

    private EconomyNetwork() {
    }

    static void register() {
        CHANNEL.registerMessage(
                0,
                EconomyRequest.class,
                EconomyRequest::encode,
                EconomyRequest::decode,
                EconomyRequest::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(
                1,
                EconomySnapshot.class,
                EconomySnapshot::encode,
                EconomySnapshot::decode,
                EconomySnapshot::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    static void sendTo(ServerPlayer player, EconomySnapshot snapshot) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), snapshot);
    }

    public static void sendToServer(EconomyRequest request) {
        CHANNEL.sendToServer(request);
    }

    public enum Operation {
        BALANCE(0),
        GRANT(1),
        REVOKE(2),
        INSPECT(3);

        private final int code;

        Operation(int code) {
            this.code = code;
        }

        int code() {
            return code;
        }

        static Operation fromCode(int code) {
            for (Operation operation : values()) {
                if (operation.code == code) {
                    return operation;
                }
            }
            return null;
        }
    }

    public static final class EconomyRequest {
        private final int protocolVersion;
        private final UUID requestId;
        private final int menuId;
        private final int operationCode;
        private final String targetName;
        private final long amount;
        private final String reason;

        private EconomyRequest(
                int protocolVersion,
                UUID requestId,
                int menuId,
                int operationCode,
                String targetName,
                long amount,
                String reason) {
            this.protocolVersion = protocolVersion;
            this.requestId = requestId;
            this.menuId = menuId;
            this.operationCode = operationCode;
            this.targetName = targetName;
            this.amount = amount;
            this.reason = reason;
        }

        public static EconomyRequest create(
                UUID requestId,
                int menuId,
                Operation operation,
                String targetName,
                long amount,
                String reason) {
            return new EconomyRequest(
                    PROTOCOL_VERSION,
                    requestId,
                    menuId,
                    operation == null ? -1 : operation.code(),
                    targetName == null ? "" : targetName,
                    amount,
                    reason == null ? "" : reason);
        }

        int protocolVersion() {
            return protocolVersion;
        }

        UUID requestId() {
            return requestId;
        }

        int menuId() {
            return menuId;
        }

        int operationCode() {
            return operationCode;
        }

        String targetName() {
            return targetName;
        }

        long amount() {
            return amount;
        }

        String reason() {
            return reason;
        }

        private static void encode(EconomyRequest request, FriendlyByteBuf buffer) {
            buffer.writeVarInt(request.protocolVersion);
            buffer.writeUUID(request.requestId);
            buffer.writeVarInt(request.menuId);
            buffer.writeVarInt(request.operationCode);
            buffer.writeUtf(request.targetName, MAX_PLAYER_NAME_LENGTH);
            buffer.writeVarLong(request.amount);
            buffer.writeUtf(request.reason, MAX_REASON_LENGTH);
        }

        private static EconomyRequest decode(FriendlyByteBuf buffer) {
            return new EconomyRequest(
                    buffer.readVarInt(),
                    buffer.readUUID(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readUtf(MAX_PLAYER_NAME_LENGTH),
                    buffer.readVarLong(),
                    buffer.readUtf(MAX_REASON_LENGTH));
        }

        private static void handle(EconomyRequest request, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer sender = context.getSender();
                if (sender != null) {
                    EconomyService.handlePacket(sender, request);
                }
            });
            context.setPacketHandled(true);
        }
    }

    public static final class EconomySnapshot {
        private final int protocolVersion;
        private final int menuId;
        private final boolean administrator;
        private final long ownBalance;
        private final boolean hasTarget;
        private final String targetName;
        private final long targetBalance;
        private final boolean success;
        private final UUID transactionId;
        private final List<String> onlinePlayers;
        private final String message;

        EconomySnapshot(
                int protocolVersion,
                int menuId,
                boolean administrator,
                long ownBalance,
                boolean hasTarget,
                String targetName,
                long targetBalance,
                boolean success,
                UUID transactionId,
                List<String> onlinePlayers,
                String message) {
            this.protocolVersion = protocolVersion;
            this.menuId = menuId;
            this.administrator = administrator;
            this.ownBalance = ownBalance;
            this.hasTarget = hasTarget;
            this.targetName = targetName;
            this.targetBalance = targetBalance;
            this.success = success;
            this.transactionId = transactionId;
            this.onlinePlayers = List.copyOf(onlinePlayers);
            this.message = message;
        }

        public int menuId() {
            return menuId;
        }

        public boolean administrator() {
            return administrator;
        }

        public long ownBalance() {
            return ownBalance;
        }

        public boolean hasTarget() {
            return hasTarget;
        }

        public String targetName() {
            return targetName;
        }

        public long targetBalance() {
            return targetBalance;
        }

        public boolean success() {
            return success;
        }

        public UUID transactionId() {
            return transactionId;
        }

        public List<String> onlinePlayers() {
            return onlinePlayers;
        }

        public String message() {
            return message;
        }

        private static void encode(EconomySnapshot snapshot, FriendlyByteBuf buffer) {
            buffer.writeVarInt(snapshot.protocolVersion);
            buffer.writeVarInt(snapshot.menuId);
            buffer.writeBoolean(snapshot.administrator);
            buffer.writeVarLong(snapshot.ownBalance);
            buffer.writeBoolean(snapshot.hasTarget);
            if (snapshot.hasTarget) {
                buffer.writeUtf(snapshot.targetName, MAX_PLAYER_NAME_LENGTH);
                buffer.writeVarLong(snapshot.targetBalance);
            }
            buffer.writeBoolean(snapshot.success);
            buffer.writeBoolean(snapshot.transactionId != null);
            if (snapshot.transactionId != null) {
                buffer.writeUUID(snapshot.transactionId);
            }
            if (snapshot.onlinePlayers.size() > MAX_ONLINE_PLAYERS) {
                throw new IllegalArgumentException("invalid Economy online player count");
            }
            buffer.writeVarInt(snapshot.onlinePlayers.size());
            for (String player : snapshot.onlinePlayers) {
                buffer.writeUtf(player, MAX_PLAYER_NAME_LENGTH);
            }
            buffer.writeUtf(snapshot.message, MAX_MESSAGE_LENGTH);
        }

        private static EconomySnapshot decode(FriendlyByteBuf buffer) {
            int protocolVersion = buffer.readVarInt();
            if (protocolVersion != PROTOCOL_VERSION) {
                throw new IllegalArgumentException("unsupported Economy snapshot protocol version");
            }
            int menuId = buffer.readVarInt();
            boolean administrator = buffer.readBoolean();
            long ownBalance = buffer.readVarLong();
            if (ownBalance < 0L) {
                throw new IllegalArgumentException("invalid Economy own balance");
            }
            boolean hasTarget = buffer.readBoolean();
            String targetName = "";
            long targetBalance = 0L;
            if (hasTarget) {
                targetName = buffer.readUtf(MAX_PLAYER_NAME_LENGTH);
                targetBalance = buffer.readVarLong();
                if (!EconomyService.validSingleWord(targetName, MAX_PLAYER_NAME_LENGTH)
                        || targetBalance < 0L) {
                    throw new IllegalArgumentException("invalid Economy target view");
                }
            }
            boolean success = buffer.readBoolean();
            UUID transactionId = buffer.readBoolean() ? buffer.readUUID() : null;
            int playerCount = buffer.readVarInt();
            if (playerCount < 0 || playerCount > MAX_ONLINE_PLAYERS) {
                throw new IllegalArgumentException("invalid Economy online player count");
            }
            java.util.ArrayList<String> onlinePlayers = new java.util.ArrayList<>();
            for (int index = 0; index < playerCount; index++) {
                String player = buffer.readUtf(MAX_PLAYER_NAME_LENGTH);
                if (!EconomyService.validSingleWord(player, MAX_PLAYER_NAME_LENGTH)) {
                    throw new IllegalArgumentException("invalid Economy online player");
                }
                onlinePlayers.add(player);
            }
            String message = buffer.readUtf(MAX_MESSAGE_LENGTH);
            return new EconomySnapshot(
                    protocolVersion,
                    menuId,
                    administrator,
                    ownBalance,
                    hasTarget,
                    targetName,
                    targetBalance,
                    success,
                    transactionId,
                    onlinePlayers,
                    message);
        }

        private static void handle(EconomySnapshot snapshot, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> () -> io.github.yu1sh.reality.economy.client.EconomyClient.receiveSnapshot(snapshot)));
            context.setPacketHandled(true);
        }
    }
}
