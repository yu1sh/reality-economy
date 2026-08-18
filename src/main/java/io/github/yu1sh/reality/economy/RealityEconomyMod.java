package io.github.yu1sh.reality.economy;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraft.world.level.Level;

@Mod(RealityEconomyMod.MOD_ID)
public final class RealityEconomyMod {
    public static final String MOD_ID = "reality_economy";
    public static final String SHOP_ID = ShopDomain.SHOP_ID;
    public static final MenuType<ShopMenu> SHOP_MENU = IForgeMenuType.create(RealityEconomyMod::createShopMenu);
    public static final MenuType<EconomyMenu> ECONOMY_MENU = IForgeMenuType.create(RealityEconomyMod::createEconomyMenu);

    public RealityEconomyMod() {
        Registry.register(BuiltInRegistries.MENU, new ResourceLocation(MOD_ID, "shop"), SHOP_MENU);
        Registry.register(BuiltInRegistries.MENU, new ResourceLocation(MOD_ID, "economy"), ECONOMY_MENU);
        ShopNetwork.register();
        EconomyNetwork.register();
        MinecraftForge.EVENT_BUS.register(this);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::enqueueQuestRewardEndpoints);
    }

    private void enqueueQuestRewardEndpoints(InterModEnqueueEvent event) {
        InterModComms.sendTo(
                QuestRewardContract.PRODUCER_MOD_ID,
                QuestRewardContract.IMC_RECEIVE_METHOD,
                () -> (BiFunction<ServerLevel, CompoundTag, CompoundTag>) QuestRewardReceiver::receive);
        InterModComms.sendTo(
                QuestRewardContract.PRODUCER_MOD_ID,
                QuestRewardContract.IMC_SCOPE_METHOD,
                () -> (Function<ServerLevel, CompoundTag>) QuestRewardReceiver::currentScope);
        InterModComms.sendTo(
                QuestRewardContract.FOUNDATION_MOD_ID,
                QuestRewardContract.FOUNDATION_HEALTH_METHOD,
                () -> (Function<MinecraftServer, CompoundTag>) QuestRewardReceiver::foundationHealth);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        QuestRewardReceiver.onServerStarting(event.getServer());
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        QuestRewardReceiver.onServerStarted(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        QuestRewardReceiver.onServerStopped();
    }

    @SubscribeEvent
    public void recoverQuestRewards(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level
                && Level.OVERWORLD.equals(level.dimension())) {
            try {
                QuestRewardReceiver.recover(level);
            } catch (RuntimeException failure) {
                QuestRewardReceiver.markInitializationFailed();
            }
        }
    }

    private static ShopMenu createShopMenu(int windowId, Inventory inventory, FriendlyByteBuf buffer) {
        return new ShopMenu(SHOP_MENU, windowId, inventory);
    }

    private static EconomyMenu createEconomyMenu(int windowId, Inventory inventory, FriendlyByteBuf buffer) {
        return new EconomyMenu(ECONOMY_MENU, windowId, inventory);
    }

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
                Commands.literal("realityeconomy")
                        .then(Commands.literal("balance")
                                .executes(RealityEconomyMod::showOwnBalance)
                                .then(Commands.literal("gui")
                                        .executes(RealityEconomyMod::openEconomy)))
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
        return EconomyService.showOwnBalance(player) ? 1 : 0;
    }

    private static int openEconomy(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = requirePlayer(context);
        if (player == null) {
            return 0;
        }
        EconomyService.openEconomy(player);
        return 1;
    }

    private static int applyMutation(CommandContext<CommandSourceStack> context, boolean grant) {
        ServerPlayer actor = requirePlayer(context);
        if (actor == null) {
            return 0;
        }

        long amount = LongArgumentType.getLong(context, "amount");
        String reason = StringArgumentType.getString(context, "reason");
        EconomyService.Result result = EconomyService.mutate(
                actor,
                grant,
                StringArgumentType.getString(context, "player"),
                amount,
                reason);
        if (!result.success()) {
            context.getSource().sendFailure(Component.literal(result.message()));
            return 0;
        }
        actor.sendSystemMessage(Component.literal(result.message()));
        return 1;
    }

    private static int inspectBalance(CommandContext<CommandSourceStack> context) {
        ServerPlayer actor = requirePlayer(context);
        if (actor == null) {
            return 0;
        }

        EconomyService.Result result = EconomyService.inspect(
                actor,
                StringArgumentType.getString(context, "player"));
        if (!result.success()) {
            context.getSource().sendFailure(Component.literal(result.message()));
            return 0;
        }
        actor.sendSystemMessage(Component.literal(result.message()));
        return 1;
    }

    private static ServerPlayer requirePlayer(CommandContext<CommandSourceStack> context) {
        if (context.getSource().getEntity() instanceof ServerPlayer player) {
            return player;
        }
        context.getSource().sendFailure(Component.literal("only a player can use this command"));
        return null;
    }

}
