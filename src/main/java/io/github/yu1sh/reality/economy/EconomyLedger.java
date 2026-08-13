package io.github.yu1sh.reality.economy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** A persistent balance ledger shared by every dimension of one server world. */
public final class EconomyLedger extends SavedData {
    static final String DATA_NAME = "reality_economy_ledger";
    private static final String BALANCES_TAG = "balances";
    private static final String PLAYER_TAG = "player";
    private static final String BALANCE_TAG = "balance";

    private final Map<UUID, Long> balances = new HashMap<>();

    private EconomyLedger() {
    }

    static EconomyLedger create() {
        return new EconomyLedger();
    }

    static EconomyLedger load(CompoundTag tag) {
        EconomyLedger ledger = new EconomyLedger();
        ListTag balanceTags = tag.getList(BALANCES_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < balanceTags.size(); index++) {
            CompoundTag balanceTag = balanceTags.getCompound(index);
            if (!balanceTag.contains(PLAYER_TAG, Tag.TAG_STRING)
                    || !balanceTag.contains(BALANCE_TAG, Tag.TAG_LONG)) {
                continue;
            }

            try {
                UUID player = UUID.fromString(balanceTag.getString(PLAYER_TAG));
                long balance = balanceTag.getLong(BALANCE_TAG);
                if (balance >= 0 && balance > 0) {
                    ledger.balances.put(player, balance);
                }
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed UUID entries without preventing the world from loading.
            }
        }
        return ledger;
    }

    /** Uses the overworld storage so every dimension in this world shares one ledger. */
    static EconomyLedger forLevel(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                EconomyLedger::load,
                EconomyLedger::create,
                DATA_NAME);
    }

    synchronized long balanceOf(UUID player) {
        return balances.getOrDefault(player, 0L);
    }

    synchronized MutationResult grant(UUID player, long amount) {
        long current = balanceOf(player);
        if (amount > Long.MAX_VALUE - current) {
            return new MutationResult(false, current);
        }

        long updated = current + amount;
        putBalance(player, updated);
        setDirty();
        return new MutationResult(true, updated);
    }

    synchronized MutationResult revoke(UUID player, long amount) {
        long current = balanceOf(player);
        if (amount > current) {
            return new MutationResult(false, current);
        }

        long updated = current - amount;
        putBalance(player, updated);
        setDirty();
        return new MutationResult(true, updated);
    }

    private void putBalance(UUID player, long balance) {
        if (balance == 0) {
            balances.remove(player);
        } else {
            balances.put(player, balance);
        }
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag) {
        ListTag balanceTags = new ListTag();
        List<Map.Entry<UUID, Long>> entries = new ArrayList<>(balances.entrySet());
        entries.sort(Comparator.comparing(entry -> entry.getKey().toString()));

        for (Map.Entry<UUID, Long> entry : entries) {
            CompoundTag balanceTag = new CompoundTag();
            balanceTag.putString(PLAYER_TAG, entry.getKey().toString());
            balanceTag.putLong(BALANCE_TAG, entry.getValue());
            balanceTags.add(balanceTag);
        }
        tag.put(BALANCES_TAG, balanceTags);
        return tag;
    }

    record MutationResult(boolean applied, long balance) {
    }
}
