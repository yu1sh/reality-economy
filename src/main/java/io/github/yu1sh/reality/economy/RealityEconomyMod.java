package io.github.yu1sh.reality.economy;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.time.Instant;
import java.util.UUID;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(RealityEconomyMod.MOD_ID)
public final class RealityEconomyMod {
    public static final String MOD_ID = "reality_economy";
    public static final String SHOP_ID = ShopDomain.SHOP_ID;
    public static final MenuType<ShopMenu> SHOP_MENU = IForgeMenuType.create(RealityEconomyMod::createShopMenu);
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public RealityEconomyMod() {
        Registry.register(BuiltInRegistries.MENU, new ResourceLocation(MOD_ID, "shop"), SHOP_MENU);
        ShopNetwork.register();
        MinecraftForge.EVENT_BUS.register(this);
    }

    private static ShopMenu createShopMenu(int windowId, Inventory inventory, FriendlyByteBuf buffer) {
        return new ShopMenu(SHOP_MENU, windowId, inventory);
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
                                                .executes(RealityEconomyMod::inspectBalance))))
                        .then(Commands.literal("shop")
                                .then(Commands.literal("open")
                                        .executes(RealityEconomyMod::openShop))
                                .then(Commands.literal("list")
                                        .executes(RealityEconomyMod::listShop))
                                .then(Commands.literal("detail")
                                        .then(Commands.argument("entry", StringArgumentType.word())
                                                .then(Commands.argument("revision", LongArgumentType.longArg(1L))
                                                        .executes(RealityEconomyMod::detailShop))))
                                .then(Commands.literal("purchase")
                                        .then(Commands.argument("entry", StringArgumentType.word())
                                                .then(Commands.argument("revision", LongArgumentType.longArg(1L))
                                                        .executes(RealityEconomyMod::purchaseShop))))
                                .then(Commands.literal("clerk")
                                        .then(Commands.literal("add")
                                                .then(Commands.argument("item", ResourceLocationArgument.id())
                                                        .then(Commands.argument(
                                                                        "quantity",
                                                                        IntegerArgumentType.integer(1, ShopDomain.MAX_QUANTITY))
                                                                .then(Commands.argument(
                                                                                "price",
                                                                                LongArgumentType.longArg(
                                                                                        ShopDomain.MIN_PRICE,
                                                                                        ShopDomain.MAX_PRICE))
                                                                        .then(Commands.argument(
                                                                                        "revision",
                                                                                        LongArgumentType.longArg(1L))
                                                                                .executes(RealityEconomyMod::addShopEntry))))))
                                        .then(Commands.literal("change")
                                                .then(Commands.argument("entry", StringArgumentType.word())
                                                        .then(Commands.argument("item", ResourceLocationArgument.id())
                                                                .then(Commands.argument(
                                                                                "quantity",
                                                                                IntegerArgumentType.integer(
                                                                                        1,
                                                                                        ShopDomain.MAX_QUANTITY))
                                                                        .then(Commands.argument(
                                                                                        "price",
                                                                                        LongArgumentType.longArg(
                                                                                                ShopDomain.MIN_PRICE,
                                                                                                ShopDomain.MAX_PRICE))
                                                                                .then(Commands.argument(
                                                                                                "revision",
                                                                                                LongArgumentType.longArg(1L))
                                                                                        .executes(RealityEconomyMod::changeShopEntry)))))))
                                        .then(Commands.literal("stop")
                                                .then(Commands.argument("entry", StringArgumentType.word())
                                                        .then(Commands.argument("revision", LongArgumentType.longArg(1L))
                                                                .executes(RealityEconomyMod::stopShopEntry))))
                                        .then(Commands.literal("resume")
                                                .then(Commands.argument("entry", StringArgumentType.word())
                                                        .then(Commands.argument("revision", LongArgumentType.longArg(1L))
                                                                .executes(RealityEconomyMod::resumeShopEntry)))))
                                .then(Commands.literal("admin")
                                        .requires(source -> source.hasPermission(2))
                                        .then(Commands.literal("appoint")
                                                .then(Commands.argument("player", StringArgumentType.word())
                                                        .executes(RealityEconomyMod::appointShopClerk)))
                                        .then(Commands.literal("revoke")
                                                .then(Commands.argument("player", StringArgumentType.word())
                                                        .executes(RealityEconomyMod::revokeShopClerk)))
                                        .then(Commands.literal("recovery")
                                                .then(Commands.literal("status")
                                                        .executes(RealityEconomyMod::recoveryStatus))
                                                .then(Commands.literal("retry")
                                                        .then(Commands.argument("purchase_id", StringArgumentType.word())
                                                                .then(Commands.argument(
                                                                                "revision",
                                                                                LongArgumentType.longArg(1L))
                                                                        .executes(RealityEconomyMod::retryRecovery))))
                                                .then(Commands.literal("resolve")
                                                        .then(Commands.argument("purchase_id", StringArgumentType.word())
                                                                .then(Commands.argument(
                                                                                "revision",
                                                                                LongArgumentType.longArg(1L))
                                                                        .executes(RealityEconomyMod::resolveRecovery))))
                                        )
                                        .then(Commands.literal("reset")
                                                .executes(RealityEconomyMod::resetShop)))));
    }

    private static int openShop(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = requirePlayer(context);
        if (player == null) {
            return 0;
        }
        ShopService.openShop(player);
        return 1;
    }

    private static int listShop(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = requirePlayer(context);
        return player == null || !ShopService.list(player) ? 0 : 1;
    }

    private static int detailShop(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = requirePlayer(context);
        if (player == null) {
            return 0;
        }
        return ShopService.detail(
                player,
                StringArgumentType.getString(context, "entry"),
                LongArgumentType.getLong(context, "revision")) ? 1 : 0;
    }

    private static int purchaseShop(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = requirePlayer(context);
        if (player == null) {
            return 0;
        }
        return ShopService.purchase(
                player,
                StringArgumentType.getString(context, "entry"),
                LongArgumentType.getLong(context, "revision")) ? 1 : 0;
    }

    private static int addShopEntry(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = requirePlayer(context);
        if (player == null) {
            return 0;
        }
        return ShopService.add(
                player,
                ResourceLocationArgument.getId(context, "item").toString(),
                IntegerArgumentType.getInteger(context, "quantity"),
                LongArgumentType.getLong(context, "price"),
                LongArgumentType.getLong(context, "revision")) ? 1 : 0;
    }

    private static int changeShopEntry(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = requirePlayer(context);
        if (player == null) {
            return 0;
        }
        return ShopService.change(
                player,
                StringArgumentType.getString(context, "entry"),
                ResourceLocationArgument.getId(context, "item").toString(),
                IntegerArgumentType.getInteger(context, "quantity"),
                LongArgumentType.getLong(context, "price"),
                LongArgumentType.getLong(context, "revision")) ? 1 : 0;
    }

    private static int stopShopEntry(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = requirePlayer(context);
        if (player == null) {
            return 0;
        }
        return ShopService.stop(
                player,
                StringArgumentType.getString(context, "entry"),
                LongArgumentType.getLong(context, "revision")) ? 1 : 0;
    }

    private static int resumeShopEntry(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = requirePlayer(context);
        if (player == null) {
            return 0;
        }
        return ShopService.resume(
                player,
                StringArgumentType.getString(context, "entry"),
                LongArgumentType.getLong(context, "revision")) ? 1 : 0;
    }

    private static int appointShopClerk(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = requirePlayer(context);
        return player == null || !ShopService.appoint(
                player,
                StringArgumentType.getString(context, "player")) ? 0 : 1;
    }

    private static int revokeShopClerk(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = requirePlayer(context);
        return player == null || !ShopService.revoke(
                player,
                StringArgumentType.getString(context, "player")) ? 0 : 1;
    }

    private static int resetShop(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = requirePlayer(context);
        return player == null || !ShopService.reset(player) ? 0 : 1;
    }

    private static int recoveryStatus(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = requirePlayer(context);
        return player == null || !ShopService.recoveryStatus(player) ? 0 : 1;
    }

    private static int retryRecovery(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = requirePlayer(context);
        if (player == null) {
            return 0;
        }
        return ShopService.retryRecovery(
                player,
                StringArgumentType.getString(context, "purchase_id"),
                LongArgumentType.getLong(context, "revision")) ? 1 : 0;
    }

    private static int resolveRecovery(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = requirePlayer(context);
        if (player == null) {
            return 0;
        }
        return ShopService.resolveRecovery(
                player,
                StringArgumentType.getString(context, "purchase_id"),
                LongArgumentType.getLong(context, "revision")) ? 1 : 0;
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
