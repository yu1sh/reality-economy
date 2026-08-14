package io.github.yu1sh.reality.economy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server-side Economy application service shared by commands and the GUI. */
final class EconomyService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RealityEconomyMod.MOD_ID);
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    private EconomyService() {
    }

    static void openEconomy(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (windowId, inventory, ignored) -> new EconomyMenu(
                        RealityEconomyMod.ECONOMY_MENU,
                        windowId,
                        inventory),
                Component.literal("Reality Economy")));
        if (player.containerMenu instanceof EconomyMenu menu) {
            sendSnapshot(player, menu, Result.success("economy GUI opened"));
        }
    }

    static Result ownBalance(ServerPlayer player) {
        long balance = EconomyLedger.forLevel(player.serverLevel()).balanceOf(player.getUUID());
        return Result.success("balance=" + balance);
    }

    static boolean showOwnBalance(ServerPlayer player) {
        Result result = ownBalance(player);
        player.sendSystemMessage(Component.literal(result.message()));
        return true;
    }

    static Result mutate(
            ServerPlayer actor,
            boolean grant,
            String targetName,
            long amount,
            String reason) {
        if (!isAdministrator(actor)) {
            return Result.failure("permission level 2 is required");
        }
        if (!validSingleWord(targetName, EconomyNetwork.MAX_PLAYER_NAME_LENGTH)) {
            return Result.failure("target player must be one online player name");
        }
        if (amount < 0L) {
            return Result.failure("amount must be nonnegative");
        }
        if (!validSingleWord(reason, EconomyNetwork.MAX_REASON_LENGTH)) {
            return Result.failure("reason must be a single word");
        }

        ServerPlayer target = findOnlinePlayer(actor, targetName);
        if (target == null) {
            return Result.failure("online player not found: " + targetName);
        }

        EconomyLedger ledger = EconomyLedger.forLevel(actor.serverLevel());
        EconomyLedger.MutationResult mutation = grant
                ? ledger.grant(target.getUUID(), amount)
                : ledger.revoke(target.getUUID(), amount);
        if (!mutation.applied()) {
            String failure = grant
                    ? "grant rejected: balance would exceed the maximum"
                    : "revoke rejected: balance would become negative";
            return Result.failureForTarget(
                    failure,
                    target.getGameProfile().getName(),
                    target.getUUID(),
                    mutation.balance());
        }

        UUID transactionId = UUID.randomUUID();
        audit(
                grant ? "grant" : "revoke",
                transactionId,
                actor,
                target,
                grant ? amount : -amount,
                reason);
        return Result.successForTarget(
                (grant ? "granted" : "revoked")
                        + " amount=" + amount
                        + " player=" + target.getGameProfile().getName()
                        + " balance=" + mutation.balance()
                        + " transaction_id=" + transactionId,
                transactionId,
                target.getGameProfile().getName(),
                target.getUUID(),
                mutation.balance());
    }

    static Result inspect(ServerPlayer actor, String targetName) {
        if (!isAdministrator(actor)) {
            return Result.failure("permission level 2 is required");
        }
        if (!validSingleWord(targetName, EconomyNetwork.MAX_PLAYER_NAME_LENGTH)) {
            return Result.failure("target player must be one online player name");
        }

        ServerPlayer target = findOnlinePlayer(actor, targetName);
        if (target == null) {
            return Result.failure("online player not found: " + targetName);
        }

        long balance = EconomyLedger.forLevel(actor.serverLevel()).balanceOf(target.getUUID());
        UUID transactionId = UUID.randomUUID();
        audit("inspect", transactionId, actor, target, 0L, "inspect");
        return Result.successForTarget(
                "player=" + target.getGameProfile().getName()
                        + " balance=" + balance
                        + " transaction_id=" + transactionId,
                transactionId,
                target.getGameProfile().getName(),
                target.getUUID(),
                balance);
    }

    static void handlePacket(ServerPlayer player, EconomyNetwork.EconomyRequest request) {
        if (request == null
                || !(player.containerMenu instanceof EconomyMenu menu)
                || menu.containerId != request.menuId()
                || !menu.ownedBy(player.getUUID())
                || !menu.stillValid(player)) {
            return;
        }

        if (request.requestId() == null || ZERO_UUID.equals(request.requestId())) {
            sendSnapshot(player, menu, Result.failure("request identity rejected"));
            return;
        }
        if (!menu.claimRequest(request.requestId())) {
            sendSnapshot(player, menu, Result.failure("request replay rejected"));
            return;
        }

        EconomyNetwork.Operation operation = EconomyNetwork.Operation.fromCode(request.operationCode());
        if (request.protocolVersion() != EconomyNetwork.PROTOCOL_VERSION
                || operation == null
                || !validOptionalWord(request.targetName(), EconomyNetwork.MAX_PLAYER_NAME_LENGTH)
                || request.amount() < 0L
                || !validOptionalWord(request.reason(), EconomyNetwork.MAX_REASON_LENGTH)) {
            sendSnapshot(player, menu, Result.failure("request contract or input bounds rejected"));
            return;
        }

        Result result = switch (operation) {
            case BALANCE -> ownBalance(player);
            case GRANT -> mutate(player, true, request.targetName(), request.amount(), request.reason());
            case REVOKE -> mutate(player, false, request.targetName(), request.amount(), request.reason());
            case INSPECT -> inspect(player, request.targetName());
        };
        sendSnapshot(player, menu, result);
    }

    static boolean isAdministrator(ServerPlayer player) {
        return player.hasPermissions(2);
    }

    static boolean validSingleWord(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character) || Character.isISOControl(character)) {
                return false;
            }
        }
        return true;
    }

    private static boolean validOptionalWord(String value, int maxLength) {
        return value == null || value.isEmpty() || validSingleWord(value, maxLength);
    }

    private static ServerPlayer findOnlinePlayer(ServerPlayer actor, String targetName) {
        return actor.server.getPlayerList().getPlayerByName(targetName);
    }

    private static void sendSnapshot(ServerPlayer player, EconomyMenu menu, Result result) {
        EconomyLedger ledger = EconomyLedger.forLevel(player.serverLevel());
        boolean administrator = isAdministrator(player);
        UUID targetId = result.targetId();
        boolean hasTarget = targetId != null;
        List<String> onlinePlayers = administrator
                ? player.server.getPlayerList().getPlayers().stream()
                        .map(current -> current.getGameProfile().getName())
                        .filter(name -> validSingleWord(name, EconomyNetwork.MAX_PLAYER_NAME_LENGTH))
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .limit(EconomyNetwork.MAX_ONLINE_PLAYERS)
                        .toList()
                : List.of();
        EconomyNetwork.sendTo(
                player,
                new EconomyNetwork.EconomySnapshot(
                        EconomyNetwork.PROTOCOL_VERSION,
                        menu.containerId,
                        administrator,
                        ledger.balanceOf(player.getUUID()),
                        hasTarget,
                        hasTarget ? result.targetName() : "",
                        hasTarget ? ledger.balanceOf(targetId) : 0L,
                        result.success(),
                        result.transactionId(),
                        onlinePlayers,
                        result.message()));
    }

    private static void audit(
            String action,
            UUID transactionId,
            ServerPlayer actor,
            ServerPlayer target,
            long delta,
            String reason) {
        LOGGER.info(
                "economy_audit action={} transaction_id={} actor_uuid={} target_uuid={} delta={} reason={} timestamp={}",
                action,
                transactionId,
                actor.getUUID(),
                target.getUUID(),
                delta,
                reason,
                Instant.now());
    }

    record Result(
            boolean success,
            String message,
            UUID transactionId,
            String targetName,
            UUID targetId,
            long targetBalance) {
        static Result success(String message) {
            return new Result(true, message, null, "", null, 0L);
        }

        static Result successForTarget(
                String message,
                UUID transactionId,
                String targetName,
                UUID targetId,
                long targetBalance) {
            return new Result(true, message, transactionId, targetName, targetId, targetBalance);
        }

        static Result failure(String message) {
            return new Result(false, message, null, "", null, 0L);
        }

        static Result failureForTarget(
                String message,
                String targetName,
                UUID targetId,
                long targetBalance) {
            return new Result(false, message, null, targetName, targetId, targetBalance);
        }
    }
}
