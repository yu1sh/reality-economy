package io.github.yu1sh.reality.economy;

import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-only P-05 reward intake. The package-private endpoint methods are
 * exported to P-05 only as Forge IMC object capabilities; they are not client
 * packets or player commands. Every mutation is performed by P-07's SavedData
 * ledger.
 */
public final class QuestRewardReceiver {
    private static final Logger LOGGER = LoggerFactory.getLogger(RealityEconomyMod.MOD_ID);
    private static volatile RuntimeState runtimeState = RuntimeState.NOT_STARTED;
    private static volatile boolean recoveryFailed;

    private enum RuntimeState {
        NOT_STARTED("not_started"),
        INITIALIZING("initializing"),
        READY("ready"),
        INITIALIZATION_FAILED("initialization_failed");

        private final String wireValue;

        RuntimeState(String wireValue) {
            this.wireValue = wireValue;
        }
    }

    private QuestRewardReceiver() {
    }

    static void onServerStarting(MinecraftServer server) {
        runtimeState = server == null
                ? RuntimeState.INITIALIZATION_FAILED
                : RuntimeState.INITIALIZING;
    }

    static void onServerStarted(MinecraftServer server) {
        runtimeState = server != null && !recoveryFailed
                ? RuntimeState.READY
                : RuntimeState.INITIALIZATION_FAILED;
    }

    static void markInitializationFailed() {
        recoveryFailed = true;
        runtimeState = RuntimeState.INITIALIZATION_FAILED;
        LOGGER.error("economy_reward_runtime_initialization_failed");
    }

    static void onServerStopped() {
        recoveryFailed = false;
        runtimeState = RuntimeState.NOT_STARTED;
    }

    static CompoundTag foundationHealth(MinecraftServer server) {
        CompoundTag report = new CompoundTag();
        report.putInt("report_version", QuestRewardContract.FOUNDATION_HEALTH_REPORT_VERSION);
        report.putString("service_id", QuestRewardContract.FOUNDATION_SERVICE_ID);
        report.putString("kind", "provider");
        report.putString("runtime_state", runtimeState.wireValue);
        report.putString("registration_issue", "none");
        ListTag endpoints = new ListTag();
        addEndpoint(endpoints, QuestRewardContract.IMC_SCOPE_METHOD,
                QuestRewardContract.REWARD_ENDPOINT_VERSION);
        addEndpoint(endpoints, QuestRewardContract.IMC_RECEIVE_METHOD,
                QuestRewardContract.REWARD_ENDPOINT_VERSION);
        report.put("endpoints", endpoints);
        return report;
    }

    private static void addEndpoint(ListTag endpoints, String method, int version) {
        CompoundTag endpoint = new CompoundTag();
        endpoint.putString("method", method);
        endpoint.putInt("version", version);
        endpoint.putString("state", "available");
        endpoints.add(endpoint);
    }

    /**
     * Returns the server-owned world scope and the producer contract constants
     * needed to construct a retryable reward envelope.
     */
    static CompoundTag currentScope(ServerLevel level) {
        EconomyLedger ledger = ledgerFor(level);
        CompoundTag scope = new CompoundTag();
        scope.putInt(QuestRewardJournal.CONTRACT_MAJOR_TAG, QuestRewardContract.CONTRACT_MAJOR);
        scope.putString(QuestRewardJournal.SOURCE_TAG, QuestRewardContract.SOURCE);
        scope.putString(QuestRewardJournal.CURRENCY_UNIT_TAG, QuestRewardContract.CURRENCY_UNIT);
        scope.putString(QuestRewardJournal.WORLD_KEY_TAG, ledger.worldKey());
        scope.putString(QuestRewardJournal.WORLD_EPOCH_TAG, ledger.currentEpoch());
        return scope;
    }

    /**
     * Receives a server-created NBT envelope and returns a server-created result
     * envelope. The field names are stable for the P-05 producer and all values
     * are revalidated before any ledger mutation.
     */
    static CompoundTag receive(ServerLevel level, CompoundTag envelope) {
        QuestRewardContract.Intent intent = QuestRewardJournal.readIntent(envelope);
        return encodeResult(receive(level, intent));
    }

    /** Typed entry point for the Forge IMC endpoint and P-07 recovery. */
    static QuestRewardContract.Result receive(
            ServerLevel level,
            QuestRewardContract.Intent intent) {
        if (level == null) {
            return result(QuestRewardContract.ResultStatus.REJECTED, "server level is missing", 0L, null, null);
        }

        EconomyLedger ledger = ledgerFor(level);
        QuestRewardContract.Key key = keyOf(intent);
        if (key == null) {
            return result(
                    QuestRewardContract.ResultStatus.REJECTED,
                    "reward key is malformed",
                    0L,
                    null,
                    null);
        }

        synchronized (ledger) {
            QuestRewardContract.JournalRecord existing = ledger.findQuestReward(key);
            if (existing != null) {
                if (!existing.intent().samePayload(intent)) {
                    ledger.recordQuestRewardConflict(
                            new QuestRewardContract.ConflictRecord(
                                    intent,
                                    key.canonical(),
                                    System.currentTimeMillis(),
                                    "immutable reward payload mismatch"));
                    persist(level, key, QuestRewardContract.ResultStatus.CONFLICT);
                    audit(key, QuestRewardContract.ResultStatus.CONFLICT, 0L, "immutable payload mismatch");
                    return result(
                            QuestRewardContract.ResultStatus.CONFLICT,
                            "same reward key has a different immutable payload",
                            existing.balance(),
                            existing.transactionId(),
                            key);
                }
                QuestRewardContract.Result existingResult = handleExisting(level, ledger, existing, key);
                audit(key, existingResult.status(), existingResult.balance(), existingResult.message());
                return existingResult;
            }

            if (isOldEpoch(ledger, intent)) {
                return freeze(
                        level,
                        ledger,
                        key,
                        intent,
                        "reward frozen after server world epoch reset");
            }

            QuestRewardContract.WorldScope expectedScope = new QuestRewardContract.WorldScope(
                    ledger.worldKey(),
                    ledger.currentEpoch());
            String validation = QuestRewardContract.validate(intent, expectedScope);
            if (!validation.isEmpty()) {
                ledger.rejectQuestReward(
                        new QuestRewardContract.JournalRecord(
                                intent,
                                QuestRewardContract.JournalStatus.REJECTED,
                                null,
                                ledger.balanceOf(intent.targetPlayer()),
                                System.currentTimeMillis(),
                                validation));
                if (!persist(level, key, QuestRewardContract.ResultStatus.REJECTED)) {
                    return result(
                            QuestRewardContract.ResultStatus.PENDING,
                            "rejection journal could not be persisted",
                            ledger.balanceOf(intent.targetPlayer()),
                            null,
                            key);
                }
                audit(key, QuestRewardContract.ResultStatus.REJECTED, ledger.balanceOf(intent.targetPlayer()), validation);
                return result(
                        QuestRewardContract.ResultStatus.REJECTED,
                        validation,
                        ledger.balanceOf(intent.targetPlayer()),
                        null,
                        key);
            }

            ledger.prepareQuestReward(
                    new QuestRewardContract.JournalRecord(
                            intent,
                            QuestRewardContract.JournalStatus.PENDING,
                            null,
                            ledger.balanceOf(intent.targetPlayer()),
                            System.currentTimeMillis(),
                            "reward prepared for atomic credit"));
            if (!persist(level, key, QuestRewardContract.ResultStatus.PENDING)) {
                return result(
                        QuestRewardContract.ResultStatus.PENDING,
                        "reward journal is pending persistence",
                        ledger.balanceOf(intent.targetPlayer()),
                        null,
                        key);
            }
            QuestRewardContract.Result applied = applyPrepared(level, ledger, key);
            audit(key, applied.status(), applied.balance(), applied.message());
            return applied;
        }
    }

    /** Reconciles current rewards and durably freezes stale ones after restart. */
    static void recover(ServerLevel level) {
        if (level == null) {
            return;
        }
        EconomyLedger ledger = ledgerFor(level);
        synchronized (ledger) {
            List<QuestRewardContract.JournalRecord> pending = ledger.pendingQuestRewards();
            boolean rejectedDuringRecovery = false;
            for (QuestRewardContract.JournalRecord record : pending) {
                QuestRewardContract.Key key = keyOf(record.intent());
                if (key == null || !isCurrentScope(ledger, record.intent())) {
                    if (key != null) {
                        freeze(
                                level,
                                ledger,
                                key,
                                record.intent(),
                                "reward frozen after server world epoch reset");
                    }
                    continue;
                }
                String validation = QuestRewardContract.validate(
                        record.intent(),
                        new QuestRewardContract.WorldScope(ledger.worldKey(), ledger.currentEpoch()));
                if (!validation.isEmpty()) {
                    ledger.markQuestRewardRejected(key, validation);
                    rejectedDuringRecovery = true;
                    continue;
                }
                applyPrepared(level, ledger, key);
            }
            if (rejectedDuringRecovery) {
                persist(level, null, QuestRewardContract.ResultStatus.REJECTED);
            }
        }
    }

    private static QuestRewardContract.Result handleExisting(
            ServerLevel level,
            EconomyLedger ledger,
            QuestRewardContract.JournalRecord existing,
            QuestRewardContract.Key key) {
        return switch (existing.status()) {
            case APPLIED -> result(
                    QuestRewardContract.ResultStatus.ALREADY_APPLIED,
                    "reward was already applied",
                    existing.balance(),
                    existing.transactionId(),
                    key);
            case REJECTED -> result(
                    QuestRewardContract.ResultStatus.REJECTED,
                    existing.reason(),
                    existing.balance(),
                    null,
                    key);
            case CONFLICT -> result(
                    QuestRewardContract.ResultStatus.CONFLICT,
                    existing.reason(),
                    existing.balance(),
                    existing.transactionId(),
                    key);
            case FROZEN -> {
                if (!persist(level, key, QuestRewardContract.ResultStatus.FROZEN)) {
                    yield result(
                            QuestRewardContract.ResultStatus.PENDING,
                            "existing freeze record is not durably confirmed; retry is required",
                            existing.balance(),
                            null,
                            key);
                }
                yield result(
                        QuestRewardContract.ResultStatus.FROZEN,
                        existing.reason(),
                        existing.balance(),
                        null,
                        key);
            }
            case PENDING -> {
                if (!isCurrentScope(ledger, existing.intent())) {
                    yield freeze(
                            level,
                            ledger,
                            key,
                            existing.intent(),
                            "reward frozen after server world epoch reset");
                }
                yield applyPrepared(level, ledger, key);
            }
        };
    }

    private static QuestRewardContract.Result applyPrepared(
            ServerLevel level,
            EconomyLedger ledger,
            QuestRewardContract.Key key) {
        EconomyLedger.QuestRewardCredit credit = ledger.applyPreparedQuestReward(key);
        if (!credit.applied()) {
            ledger.markQuestRewardRejected(key, credit.reason());
            if (!persist(level, key, QuestRewardContract.ResultStatus.REJECTED)) {
                return result(
                        QuestRewardContract.ResultStatus.PENDING,
                        "reward rejection could not be persisted",
                        credit.balance(),
                        null,
                        key);
            }
            return result(
                    QuestRewardContract.ResultStatus.REJECTED,
                    credit.reason(),
                    credit.balance(),
                    null,
                    key);
        }
        if (!persist(level, key, QuestRewardContract.ResultStatus.APPLIED)) {
            return result(
                    QuestRewardContract.ResultStatus.PENDING,
                    "credit is awaiting durable journal confirmation",
                    credit.balance(),
                    credit.transactionId(),
                    key);
        }
        return result(
                QuestRewardContract.ResultStatus.APPLIED,
                "reward credit applied",
                credit.balance(),
                credit.transactionId(),
                key);
    }

    private static EconomyLedger ledgerFor(ServerLevel level) {
        EconomyLedger ledger = EconomyLedger.forLevel(level);
        String shopEpoch = ShopData.forLevel(level).currentEpoch();
        ledger.ensureEpoch(shopEpoch);
        return ledger;
    }

    private static boolean isCurrentScope(EconomyLedger ledger, QuestRewardContract.Intent intent) {
        return ledger.worldKey().equals(intent.worldKey())
                && ledger.currentEpoch().equals(intent.worldEpoch());
    }

    private static boolean isOldEpoch(
            EconomyLedger ledger,
            QuestRewardContract.Intent intent) {
        if (intent == null
                || ledger.currentEpoch().isBlank()
                || !ledger.worldKey().equals(intent.worldKey())
                || ledger.currentEpoch().equals(intent.worldEpoch())) {
            return false;
        }
        return QuestRewardContract.validate(
                        intent,
                        new QuestRewardContract.WorldScope(
                                intent.worldKey(),
                                intent.worldEpoch()))
                .isEmpty();
    }

    private static QuestRewardContract.Result freeze(
            ServerLevel level,
            EconomyLedger ledger,
            QuestRewardContract.Key key,
            QuestRewardContract.Intent intent,
            String reason) {
        QuestRewardContract.JournalRecord prior = ledger.findQuestReward(key);
        if (prior != null && prior.status() != QuestRewardContract.JournalStatus.PENDING) {
            if (prior.status() != QuestRewardContract.JournalStatus.FROZEN
                    || !persist(level, key, QuestRewardContract.ResultStatus.FROZEN)) {
                return result(
                        QuestRewardContract.ResultStatus.PENDING,
                        "existing freeze record is not durably confirmed; retry is required",
                        prior.balance(),
                        null,
                        key);
            }
            return result(
                    QuestRewardContract.ResultStatus.FROZEN,
                    prior.reason(),
                    prior.balance(),
                    null,
                    key);
        }
        QuestRewardContract.JournalRecord frozen = ledger.freezeQuestReward(key, intent, reason);
        long balance = frozen == null
                ? ledger.balanceOf(intent.targetPlayer())
                : frozen.balance();
        if (frozen == null) {
            LOGGER.error("economy_quest_reward_freeze_failed key={}", key.canonical());
            return result(
                    QuestRewardContract.ResultStatus.PENDING,
                    "reward freeze could not be prepared; retry is required",
                    balance,
                    null,
                    key);
        }
        if (!persist(level, key, QuestRewardContract.ResultStatus.FROZEN)) {
            ledger.rollbackQuestRewardFreeze(key, intent);
            return result(
                    QuestRewardContract.ResultStatus.PENDING,
                    "reward freeze record is not durable; retry is required",
                    balance,
                    null,
                    key);
        }
        audit(key, QuestRewardContract.ResultStatus.FROZEN, balance, frozen.reason());
        reason = frozen.reason();
        return result(
                QuestRewardContract.ResultStatus.FROZEN,
                reason,
                balance,
                null,
                key);
    }

    private static QuestRewardContract.Key keyOf(QuestRewardContract.Intent intent) {
        return intent == null ? null : intent.key();
    }

    private static boolean persist(
            ServerLevel level,
            QuestRewardContract.Key key,
            QuestRewardContract.ResultStatus status) {
        try {
            level.getServer().overworld().getDataStorage().save();
            return true;
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "economy_quest_reward_persist_failed key={} status={}",
                    key == null ? "none" : key.canonical(),
                    status,
                    exception);
            return false;
        }
    }

    private static void audit(
            QuestRewardContract.Key key,
            QuestRewardContract.ResultStatus status,
            long balance,
            String reason) {
        LOGGER.info(
                "economy_quest_reward_audit key={} source={} status={} balance={} reason={} timestamp={}",
                key == null ? "none" : key.canonical(),
                QuestRewardContract.SOURCE,
                status,
                balance,
                reason,
                System.currentTimeMillis());
    }

    private static QuestRewardContract.Result result(
            QuestRewardContract.ResultStatus status,
            String message,
            long balance,
            java.util.UUID transactionId,
            QuestRewardContract.Key key) {
        return new QuestRewardContract.Result(
                status,
                message,
                balance,
                transactionId,
                key == null ? "" : key.canonical());
    }

    private static CompoundTag encodeResult(QuestRewardContract.Result result) {
        CompoundTag tag = new CompoundTag();
        tag.putString("status", result.status().name());
        tag.putString("message", result.message());
        tag.putLong("balance", result.balance());
        tag.putString(
                "ledger_transaction",
                result.transactionId() == null ? "" : result.transactionId().toString());
        tag.putString("key", result.key());
        return tag;
    }
}
