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
    private static final String WORLD_KEY_TAG = "world_key";
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
    private final Map<String, QuestRewardContract.JournalRecord> questRewards = new HashMap<>();
    private final List<QuestRewardContract.ConflictRecord> questRewardConflicts = new ArrayList<>();
    private final Map<String, LedgerArchive> archivedEpochs = new HashMap<>();
    private String worldKey = "";
    private String currentEpoch = "";

    private EconomyLedger() {
    }

    static EconomyLedger create() {
        EconomyLedger ledger = new EconomyLedger();
        ledger.worldKey = UUID.randomUUID().toString();
        return ledger;
    }

    static EconomyLedger load(CompoundTag tag) {
        EconomyLedger ledger = new EconomyLedger();
        if (tag.contains(WORLD_KEY_TAG, Tag.TAG_STRING)) {
            ledger.worldKey = tag.getString(WORLD_KEY_TAG);
        }
        if (tag.contains(CURRENT_EPOCH_TAG, Tag.TAG_STRING)) {
            ledger.currentEpoch = tag.getString(CURRENT_EPOCH_TAG);
        }
        readBalances(tag, ledger.balances);
        readDebits(tag, ledger.shopDebits);
        readQuestRewards(tag, ledger.questRewards);
        readQuestRewardConflicts(tag, ledger.questRewardConflicts);

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
            Map<String, QuestRewardContract.JournalRecord> archivedRewards = new HashMap<>();
            List<QuestRewardContract.ConflictRecord> archivedConflicts = new ArrayList<>();
            readBalances(archiveTag, archivedBalances);
            readDebits(archiveTag, archivedDebits);
            readQuestRewards(archiveTag, archivedRewards);
            readQuestRewardConflicts(archiveTag, archivedConflicts);
            ledger.archivedEpochs.put(
                    epoch,
                    new LedgerArchive(archivedBalances, archivedDebits, archivedRewards, archivedConflicts));
        }
        return ledger;
    }

    /** Uses the overworld storage so every dimension in this world shares one ledger. */
    static EconomyLedger forLevel(ServerLevel level) {
        EconomyLedger ledger = level.getServer().overworld().getDataStorage().computeIfAbsent(
                EconomyLedger::load,
                EconomyLedger::create,
                DATA_NAME);
        if (ledger.worldKey.isBlank()) {
            ledger.worldKey = UUID.randomUUID().toString();
            ledger.setDirty();
        }
        return ledger;
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

    synchronized String worldKey() {
        return worldKey;
    }

    synchronized QuestRewardContract.JournalRecord findQuestReward(QuestRewardContract.Key key) {
        if (key == null) {
            return null;
        }
        QuestRewardContract.JournalRecord current = questRewards.get(key.canonical());
        if (current != null) {
            return current;
        }
        for (LedgerArchive archive : archivedEpochs.values()) {
            QuestRewardContract.JournalRecord archived = archive.rewards().get(key.canonical());
            if (archived != null) {
                return archived;
            }
        }
        return null;
    }

    synchronized void prepareQuestReward(QuestRewardContract.JournalRecord record) {
        questRewards.put(record.intent().key().canonical(), record);
        setDirty();
    }

    synchronized void rejectQuestReward(QuestRewardContract.JournalRecord record) {
        questRewards.put(record.intent().key().canonical(), record);
        setDirty();
    }

    synchronized void markQuestRewardRejected(QuestRewardContract.Key key, String reason) {
        QuestRewardContract.JournalRecord previous = questRewards.get(key.canonical());
        if (previous == null) {
            return;
        }
        questRewards.put(
                key.canonical(),
                new QuestRewardContract.JournalRecord(
                        previous.intent(),
                        QuestRewardContract.JournalStatus.REJECTED,
                        null,
                        balanceOf(previous.intent().targetPlayer()),
                        System.currentTimeMillis(),
                        reason));
        setDirty();
    }

    synchronized QuestRewardCredit applyPreparedQuestReward(QuestRewardContract.Key key) {
        QuestRewardContract.JournalRecord record = questRewards.get(key.canonical());
        if (record == null || record.status() != QuestRewardContract.JournalStatus.PENDING) {
            long balance = record == null ? 0L : record.balance();
            return new QuestRewardCredit(false, null, balance, "reward is not pending");
        }
        long amount = record.intent().rewardAmount();
        long current = balanceOf(record.intent().targetPlayer());
        if (amount <= 0L || amount > Long.MAX_VALUE - current) {
            return new QuestRewardCredit(false, null, current, "ledger balance would overflow");
        }
        UUID transactionId = UUID.randomUUID();
        long updatedBalance = current + amount;
        putBalance(record.intent().targetPlayer(), updatedBalance);
        questRewards.put(
                key.canonical(),
                new QuestRewardContract.JournalRecord(
                        record.intent(),
                        QuestRewardContract.JournalStatus.APPLIED,
                        transactionId,
                        updatedBalance,
                        System.currentTimeMillis(),
                        "ledger credit and reward journal committed"));
        setDirty();
        return new QuestRewardCredit(true, transactionId, updatedBalance, "");
    }

    synchronized List<QuestRewardContract.JournalRecord> pendingQuestRewards() {
        List<QuestRewardContract.JournalRecord> pending = new ArrayList<>();
        for (QuestRewardContract.JournalRecord record : questRewards.values()) {
            if (record.status() == QuestRewardContract.JournalStatus.PENDING) {
                pending.add(record);
            }
        }
        return pending;
    }

    synchronized void recordQuestRewardConflict(QuestRewardContract.ConflictRecord conflict) {
        questRewardConflicts.add(conflict);
        setDirty();
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
                    new LedgerArchive(
                            new HashMap<>(balances),
                            new HashMap<>(shopDebits),
                            new HashMap<>(questRewards),
                            new ArrayList<>(questRewardConflicts)));
        }
        balances.clear();
        shopDebits.clear();
        questRewards.clear();
        questRewardConflicts.clear();
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
        tag.putString(WORLD_KEY_TAG, worldKey);
        tag.putString(CURRENT_EPOCH_TAG, currentEpoch);
        writeBalances(tag, balances);
        writeDebits(tag, shopDebits);
        writeQuestRewards(tag, questRewards.values());
        writeQuestRewardConflicts(tag, questRewardConflicts);

        ListTag archiveTags = new ListTag();
        List<Map.Entry<String, LedgerArchive>> archives = new ArrayList<>(archivedEpochs.entrySet());
        archives.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, LedgerArchive> archive : archives) {
            CompoundTag archiveTag = new CompoundTag();
            archiveTag.putString(EPOCH_TAG, archive.getKey());
            writeBalances(archiveTag, archive.getValue().balances());
            writeDebits(archiveTag, archive.getValue().debits());
            writeQuestRewards(archiveTag, archive.getValue().rewards().values());
            writeQuestRewardConflicts(archiveTag, archive.getValue().conflicts());
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

    private static void readQuestRewards(
            CompoundTag tag,
            Map<String, QuestRewardContract.JournalRecord> destination) {
        ListTag rewardTags = tag.getList(QuestRewardJournal.REWARDS_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < rewardTags.size(); index++) {
            QuestRewardContract.JournalRecord record = QuestRewardJournal.readRecord(
                    rewardTags.getCompound(index));
            if (record != null) {
                destination.put(record.intent().key().canonical(), record);
            }
        }
    }

    private static void readQuestRewardConflicts(
            CompoundTag tag,
            List<QuestRewardContract.ConflictRecord> destination) {
        ListTag conflictTags = tag.getList(QuestRewardJournal.CONFLICTS_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < conflictTags.size(); index++) {
            QuestRewardContract.ConflictRecord conflict = QuestRewardJournal.readConflict(
                    conflictTags.getCompound(index));
            if (conflict != null) {
                destination.add(conflict);
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

    private static void writeQuestRewards(
            CompoundTag tag,
            java.util.Collection<QuestRewardContract.JournalRecord> source) {
        ListTag rewardTags = new ListTag();
        List<QuestRewardContract.JournalRecord> records = new ArrayList<>(source);
        records.sort(Comparator.comparing(record -> record.intent().key().canonical()));
        for (QuestRewardContract.JournalRecord record : records) {
            rewardTags.add(QuestRewardJournal.writeRecord(record));
        }
        tag.put(QuestRewardJournal.REWARDS_TAG, rewardTags);
    }

    private static void writeQuestRewardConflicts(
            CompoundTag tag,
            List<QuestRewardContract.ConflictRecord> source) {
        ListTag conflictTags = new ListTag();
        for (QuestRewardContract.ConflictRecord conflict : source) {
            conflictTags.add(QuestRewardJournal.writeConflict(conflict));
        }
        tag.put(QuestRewardJournal.CONFLICTS_TAG, conflictTags);
    }

    record MutationResult(boolean applied, long balance) {
    }

    record DebitResult(boolean applied, boolean alreadyApplied, boolean conflict, long balance) {
    }

    record QuestRewardCredit(boolean applied, UUID transactionId, long balance, String reason) {
    }

    enum DebitInspection {
        ABSENT,
        MATCHING,
        CONFLICT
    }

    private record DebitRecord(UUID player, long amount) {
    }

    private record LedgerArchive(
            Map<UUID, Long> balances,
            Map<UUID, DebitRecord> debits,
            Map<String, QuestRewardContract.JournalRecord> rewards,
            List<QuestRewardContract.ConflictRecord> conflicts) {
    }
}
