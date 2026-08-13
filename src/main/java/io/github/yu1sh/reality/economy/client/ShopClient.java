package io.github.yu1sh.reality.economy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import io.github.yu1sh.reality.economy.RealityEconomyMod;
import io.github.yu1sh.reality.economy.ShopMenu;
import io.github.yu1sh.reality.economy.ShopNetwork;

/** Client adapter only; it renders server snapshots and emits untrusted requests. */
@Mod.EventBusSubscriber(
        modid = RealityEconomyMod.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT)
public final class ShopClient {
    private ShopClient() {
    }

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(RealityEconomyMod.SHOP_MENU, ShopScreen::new));
    }

    public static void receiveSnapshot(ShopNetwork.ShopSnapshot snapshot) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null
                || !(minecraft.player.containerMenu instanceof ShopMenu menu)
                || menu.containerId != snapshot.menuId()) {
            return;
        }
        menu.setSnapshot(snapshot);
        if (minecraft.screen instanceof ShopScreen screen) {
            screen.onServerSnapshot();
        }
    }
}
