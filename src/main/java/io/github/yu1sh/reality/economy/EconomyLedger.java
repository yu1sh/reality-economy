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
    private static final String CURRENT_EPOCH_TAG = "current_epoch";
    private static final String ARCHIVED_EPOCHS_TAG = "archived_epochs";
    private static final String EPOCH_TAG = "epoch";
    private static final String BALANCES_TAG = "balances";
    private static final String DEBITS_TAG = "shop_debits";
    private static final String PLAYER_TAG = "player";
    private static final String BALANCE_TAG = "balance";
    private static final String TRANSACTION_TAG = "transaction";
    private static final String AMOUNT_TAG = "amount";

    private final Map<UUID, Long> balances = new HashMap<>();
    private final Map<UUID, DebitRecord> shopDebits = new HashMap<>();
    private final Map<String, LedgerArchive> archivedEpochs = new HashMap<>();
    private String currentEpoch = "";

    private EconomyLedger() {
    }

    static EconomyLedger create() {
        return new EconomyLedger();
    }

    static EconomyLedger load(CompoundTag tag) {
        EconomyLedger ledger = new EconomyLedger();
        if (tag.contains(CURRENT_EPOCH_TAG, Tag.TAG_STRING)) {
            ledger.currentEpoch = tag.getString(CURRENT_EPOCH_TAG);
        }
        readBalances(tag, ledger.balances);
        readDebits(tag, ledger.shopDebits);

        ListTag archiveTags = tag.getList(ARCHIVED_EPOCHS_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < archiveTags.size(); index++) {
            CompoundTag archiveTag = archiveTags.getCompound(index);
            if (!archiveTag.contains(EPOCH_TAG, Tag.TAG_STRING)) {
                continue;
            }
            String epoch = archiveTag.getString(EPOCH_TAG);
            if (epoch.isBlank() || ledger.archivedEpochs.containsKey(epoch)) {
                continue;
            }
            Map<UUID, Long> archivedBalances = new HashMap<>();
            Map<UUID, DebitRecord> archivedDebits = new HashMap<>();
            readBalances(archiveTag, archivedBalances);
            readDebits(archiveTag, archivedDebits);
            ledger.archivedEpochs.put(epoch, new LedgerArchive(archivedBalances, archivedDebits));
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

    /**
     * Applies one server-generated shop debit exactly once. The transaction ID is
     * persisted with the ledger so a retry cannot debit twice after a restart.
     */
    synchronized DebitResult debitForPurchase(UUID player, long amount, UUID transactionId) {
        DebitRecord previous = shopDebits.get(transactionId);
        if (previous != null) {
            boolean sameRequest = previous.player().equals(player) && previous.amount() == amount;
            return new DebitResult(sameRequest, sameRequest, !sameRequest, balanceOf(player));
        }
        if (amount <= 0) {
            return new DebitResult(false, false, false, balanceOf(player));
        }

        long current = balanceOf(player);
        if (amount > current) {
            return new DebitResult(false, false, false, current);
        }

        putBalance(player, current - amount);
        shopDebits.put(transactionId, new DebitRecord(player, amount));
        setDirty();
        return new DebitResult(true, false, false, current - amount);
    }

    synchronized boolean hasPurchaseDebit(UUID transactionId) {
        return shopDebits.containsKey(transactionId);
    }

    synchronized DebitInspection inspectPurchaseDebit(UUID player, long amount, UUID transactionId) {
        DebitRecord previous = shopDebits.get(transactionId);
        if (previous == null) {
            return DebitInspection.ABSENT;
        }
        return previous.player().equals(player) && previous.amount() == amount
                ? DebitInspection.MATCHING
                : DebitInspection.CONFLICT;
    }

    synchronized String currentEpoch() {
        return currentEpoch;
    }

    /** Aligns the ledger namespace with the Shop world epoch. Old balances remain archived. */
    synchronized void ensureEpoch(String epoch) {
        if (epoch == null || epoch.isBlank() || epoch.equals(currentEpoch)) {
            return;
        }
        if (currentEpoch.isBlank()) {
            currentEpoch = epoch;
            setDirty();
            return;
        }
        resetToEpoch(epoch);
    }

    synchronized void resetToEpoch(String epoch) {
        if (epoch == null || epoch.isBlank() || epoch.equals(currentEpoch)) {
            return;
        }
        if (!currentEpoch.isBlank()) {
            archivedEpochs.put(
                    currentEpoch,
                    new LedgerArchive(new HashMap<>(balances), new HashMap<>(shopDebits)));
        }
        balances.clear();
        shopDebits.clear();
        currentEpoch = epoch;
        setDirty();
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
        tag.putString(CURRENT_EPOCH_TAG, currentEpoch);
        writeBalances(tag, balances);
        writeDebits(tag, shopDebits);

        ListTag archiveTags = new ListTag();
        List<Map.Entry<String, LedgerArchive>> archives = new ArrayList<>(archivedEpochs.entrySet());
        archives.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, LedgerArchive> archive : archives) {
            CompoundTag archiveTag = new CompoundTag();
            archiveTag.putString(EPOCH_TAG, archive.getKey());
            writeBalances(archiveTag, archive.getValue().balances());
            writeDebits(archiveTag, archive.getValue().debits());
            archiveTags.add(archiveTag);
        }
        tag.put(ARCHIVED_EPOCHS_TAG, archiveTags);
        return tag;
    }

    private static void readBalances(CompoundTag tag, Map<UUID, Long> destination) {
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
                if (balance > 0) {
                    destination.put(player, balance);
                }
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed UUID entries without preventing the world from loading.
            }
        }
    }

    private static void readDebits(CompoundTag tag, Map<UUID, DebitRecord> destination) {
        ListTag debitTags = tag.getList(DEBITS_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < debitTags.size(); index++) {
            CompoundTag debitTag = debitTags.getCompound(index);
            if (!debitTag.contains(TRANSACTION_TAG, Tag.TAG_STRING)
                    || !debitTag.contains(PLAYER_TAG, Tag.TAG_STRING)
                    || !debitTag.contains(AMOUNT_TAG, Tag.TAG_LONG)) {
                continue;
            }
            try {
                UUID transactionId = UUID.fromString(debitTag.getString(TRANSACTION_TAG));
                UUID player = UUID.fromString(debitTag.getString(PLAYER_TAG));
                long amount = debitTag.getLong(AMOUNT_TAG);
                if (amount > 0) {
                    destination.put(transactionId, new DebitRecord(player, amount));
                }
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed purchase debit records without preventing the world from loading.
            }
        }
    }

    private static void writeBalances(CompoundTag tag, Map<UUID, Long> source) {
        ListTag balanceTags = new ListTag();
        List<Map.Entry<UUID, Long>> entries = new ArrayList<>(source.entrySet());
        entries.sort(Comparator.comparing(entry -> entry.getKey().toString()));
        for (Map.Entry<UUID, Long> entry : entries) {
            CompoundTag balanceTag = new CompoundTag();
            balanceTag.putString(PLAYER_TAG, entry.getKey().toString());
            balanceTag.putLong(BALANCE_TAG, entry.getValue());
            balanceTags.add(balanceTag);
        }
        tag.put(BALANCES_TAG, balanceTags);
    }

    private static void writeDebits(CompoundTag tag, Map<UUID, DebitRecord> source) {
        ListTag debitTags = new ListTag();
        List<Map.Entry<UUID, DebitRecord>> debits = new ArrayList<>(source.entrySet());
        debits.sort(Comparator.comparing(entry -> entry.getKey().toString()));
        for (Map.Entry<UUID, DebitRecord> entry : debits) {
            CompoundTag debitTag = new CompoundTag();
            debitTag.putString(TRANSACTION_TAG, entry.getKey().toString());
            debitTag.putString(PLAYER_TAG, entry.getValue().player().toString());
            debitTag.putLong(AMOUNT_TAG, entry.getValue().amount());
            debitTags.add(debitTag);
        }
        tag.put(DEBITS_TAG, debitTags);
    }

    record MutationResult(boolean applied, long balance) {
    }

    record DebitResult(boolean applied, boolean alreadyApplied, boolean conflict, long balance) {
    }

    enum DebitInspection {
        ABSENT,
        MATCHING,
        CONFLICT
    }

    private record DebitRecord(UUID player, long amount) {
    }

    private record LedgerArchive(Map<UUID, Long> balances, Map<UUID, DebitRecord> debits) {
    }
}
