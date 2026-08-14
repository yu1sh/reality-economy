package io.github.yu1sh.reality.economy.client;

import io.github.yu1sh.reality.economy.EconomyMenu;
import io.github.yu1sh.reality.economy.EconomyNetwork;
import io.github.yu1sh.reality.economy.RealityEconomyMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Client adapter only; it renders server snapshots and emits untrusted requests. */
@Mod.EventBusSubscriber(
        modid = RealityEconomyMod.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT)
public final class EconomyClient {
    private EconomyClient() {
    }

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(RealityEconomyMod.ECONOMY_MENU, EconomyScreen::new));
    }

    public static void receiveSnapshot(EconomyNetwork.EconomySnapshot snapshot) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null
                || !(minecraft.player.containerMenu instanceof EconomyMenu menu)
                || menu.containerId != snapshot.menuId()) {
            return;
        }
        menu.setSnapshot(snapshot);
        if (minecraft.screen instanceof EconomyScreen screen) {
            screen.onServerSnapshot();
        }
    }
}
