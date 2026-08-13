package io.github.yu1sh.reality.economy;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.time.Instant;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(RealityEconomyMod.MOD_ID)
public final class RealityEconomyMod {
    public static final String MOD_ID = "reality_economy";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public RealityEconomyMod() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
                Commands.literal("realityeconomy")
                        .then(Commands.literal("balance")
                                .executes(RealityEconomyMod::showOwnBalance))
                        .then(Commands.literal("admin")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.literal("grant")
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .then(Commands.argument("amount", LongArgumentType.longArg(0L))
                                                        .then(Commands.argument("reason", StringArgumentType.word())
                                                                .executes(context -> applyMutation(context, true))))))
                                .then(Commands.literal("revoke")
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .then(Commands.argument("amount", LongArgumentType.longArg(0L))
                                                        .then(Commands.argument("reason", StringArgumentType.word())
                                                                .executes(context -> applyMutation(context, false))))))
                                .then(Commands.literal("inspect")
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .executes(RealityEconomyMod::inspectBalance)))));
    }

    private static int showOwnBalance(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = requirePlayer(context);
        if (player == null) {
            return 0;
        }

        long balance = EconomyLedger.forLevel(player.serverLevel()).balanceOf(player.getUUID());
        player.sendSystemMessage(Component.literal("balance=" + balance));
        return 1;
    }

    private static int applyMutation(CommandContext<CommandSourceStack> context, boolean grant) {
        ServerPlayer actor = requirePlayer(context);
        if (actor == null) {
            return 0;
        }

        ServerPlayer target = findOnlinePlayer(context);
        if (target == null) {
            return 0;
        }

        long amount = LongArgumentType.getLong(context, "amount");
        String reason = StringArgumentType.getString(context, "reason");
        EconomyLedger ledger = EconomyLedger.forLevel(actor.serverLevel());
        EconomyLedger.MutationResult result = grant
                ? ledger.grant(target.getUUID(), amount)
                : ledger.revoke(target.getUUID(), amount);
        if (!result.applied()) {
            String failure = grant
                    ? "grant rejected: balance would exceed the maximum"
                    : "revoke rejected: balance would become negative";
            context.getSource().sendFailure(Component.literal(failure));
            return 0;
        }

        UUID transactionId = UUID.randomUUID();
        long delta = grant ? amount : -amount;
        audit(
                grant ? "grant" : "revoke",
                transactionId,
                actor,
                target,
                delta,
                reason);
        actor.sendSystemMessage(Component.literal(
                (grant ? "granted" : "revoked")
                        + " amount=" + amount
                        + " player=" + target.getGameProfile().getName()
                        + " balance=" + result.balance()
                        + " transaction_id=" + transactionId));
        return 1;
    }

    private static int inspectBalance(CommandContext<CommandSourceStack> context) {
        ServerPlayer actor = requirePlayer(context);
        if (actor == null) {
            return 0;
        }

        ServerPlayer target = findOnlinePlayer(context);
        if (target == null) {
            return 0;
        }

        long balance = EconomyLedger.forLevel(actor.serverLevel()).balanceOf(target.getUUID());
        UUID transactionId = UUID.randomUUID();
        audit("inspect", transactionId, actor, target, 0L, "inspect");
        actor.sendSystemMessage(Component.literal(
                "player=" + target.getGameProfile().getName()
                        + " balance=" + balance
                        + " transaction_id=" + transactionId));
        return 1;
    }

    private static ServerPlayer requirePlayer(CommandContext<CommandSourceStack> context) {
        if (context.getSource().getEntity() instanceof ServerPlayer player) {
            return player;
        }
        context.getSource().sendFailure(Component.literal("only a player can use this command"));
        return null;
    }

    private static ServerPlayer findOnlinePlayer(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "player");
        ServerPlayer target = context.getSource().getServer().getPlayerList().getPlayerByName(name);
        if (target == null) {
            context.getSource().sendFailure(Component.literal("online player not found: " + name));
        }
        return target;
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
}
