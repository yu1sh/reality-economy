package io.github.yu1sh.reality.economy;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** NBT codec for the durable P-05 reward journal and its conflict audit entries. */
final class QuestRewardJournal {
    static final String REWARDS_TAG = "quest_reward_journal";
    static final String FROZEN_REWARDS_TAG = "quest_reward_freezes";
    static final String CONFLICTS_TAG = "quest_reward_conflicts";

    static final String CONTRACT_MAJOR_TAG = "contract_major";
    static final String SOURCE_TAG = "source";
    static final String CURRENCY_UNIT_TAG = "currency_unit";
    static final String WORLD_KEY_TAG = "world_key";
    static final String WORLD_EPOCH_TAG = "world_epoch";
    static final String PLAYER_TAG = "target_player";
    static final String QUEST_ID_TAG = "quest_id";
    static final String DEFINITION_REVISION_TAG = "definition_revision";
    static final String COMPLETION_ID_TAG = "completion_id";
    static final String AMOUNT_TAG = "reward_amount";
    static final String SOURCE_DIMENSION_TAG = "source_dimension";
    static final String COMPLETION_TIMESTAMP_TAG = "completion_timestamp";
    static final String INTENT_TIMESTAMP_TAG = "intent_timestamp";

    static final String STATUS_TAG = "status";
    static final String TRANSACTION_TAG = "ledger_transaction";
    static final String BALANCE_TAG = "balance";
    static final String RECORDED_AT_TAG = "recorded_at";
    static final String REASON_TAG = "reason";
    static final String FREEZE_ID_TAG = "freeze_id";
    static final String FREEZE_TARGET_TAG = "freeze_target_player";
    static final String EXISTING_KEY_TAG = "existing_key";

    private QuestRewardJournal() {
    }

    static void writeIntent(CompoundTag tag, QuestRewardContract.Intent intent) {
        tag.putInt(CONTRACT_MAJOR_TAG, intent.contractMajor());
        tag.putString(SOURCE_TAG, intent.source());
        tag.putString(CURRENCY_UNIT_TAG, intent.currencyUnit());
        tag.putString(WORLD_KEY_TAG, intent.worldKey());
        tag.putString(WORLD_EPOCH_TAG, intent.worldEpoch());
        tag.putString(PLAYER_TAG, intent.targetPlayer().toString());
        tag.putString(QUEST_ID_TAG, intent.questId());
        tag.putLong(DEFINITION_REVISION_TAG, intent.definitionRevision());
        tag.putString(COMPLETION_ID_TAG, intent.completionId());
        tag.putLong(AMOUNT_TAG, intent.rewardAmount());
        tag.putString(SOURCE_DIMENSION_TAG, intent.sourceDimension());
        tag.putLong(COMPLETION_TIMESTAMP_TAG, intent.completionTimestamp());
        tag.putLong(INTENT_TIMESTAMP_TAG, intent.intentTimestamp());
    }

    static QuestRewardContract.Intent readIntent(CompoundTag tag) {
        if (tag == null || !tag.contains(CONTRACT_MAJOR_TAG, Tag.TAG_INT)
                || !tag.contains(DEFINITION_REVISION_TAG, Tag.TAG_LONG)
                || !tag.contains(AMOUNT_TAG, Tag.TAG_LONG)
                || !tag.contains(COMPLETION_TIMESTAMP_TAG, Tag.TAG_LONG)
                || !tag.contains(INTENT_TIMESTAMP_TAG, Tag.TAG_LONG)) {
            return null;
        }

        String source = readString(tag, SOURCE_TAG, QuestRewardContract.MAX_SOURCE_LENGTH);
        String currencyUnit = readString(tag, CURRENCY_UNIT_TAG, QuestRewardContract.MAX_CURRENCY_LENGTH);
        String worldKey = readString(tag, WORLD_KEY_TAG, QuestRewardContract.MAX_WORLD_KEY_LENGTH);
        String worldEpoch = readString(tag, WORLD_EPOCH_TAG, QuestRewardContract.MAX_WORLD_EPOCH_LENGTH);
        UUID targetPlayer = readUuid(tag, PLAYER_TAG);
        String questId = readString(tag, QUEST_ID_TAG, QuestRewardContract.MAX_QUEST_ID_LENGTH);
        String completionId = readString(tag, COMPLETION_ID_TAG, QuestRewardContract.MAX_COMPLETION_ID_LENGTH);
        String sourceDimension = readString(tag, SOURCE_DIMENSION_TAG, QuestRewardContract.MAX_DIMENSION_LENGTH);
        if (source == null || currencyUnit == null || worldKey == null || worldEpoch == null
                || targetPlayer == null || questId == null || completionId == null || sourceDimension == null) {
            return null;
        }
        return new QuestRewardContract.Intent(
                tag.getInt(CONTRACT_MAJOR_TAG),
                source,
                currencyUnit,
                worldKey,
                worldEpoch,
                targetPlayer,
                questId,
                tag.getLong(DEFINITION_REVISION_TAG),
                completionId,
                tag.getLong(AMOUNT_TAG),
                sourceDimension,
                tag.getLong(COMPLETION_TIMESTAMP_TAG),
                tag.getLong(INTENT_TIMESTAMP_TAG));
    }

    static CompoundTag writeRecord(QuestRewardContract.JournalRecord record) {
        CompoundTag tag = new CompoundTag();
        writeIntent(tag, record.intent());
        tag.putString(STATUS_TAG, record.status().name());
        if (record.transactionId() != null) {
            tag.putString(TRANSACTION_TAG, record.transactionId().toString());
        }
        tag.putLong(BALANCE_TAG, record.balance());
        tag.putLong(RECORDED_AT_TAG, record.recordedAt());
        tag.putString(REASON_TAG, record.reason());
        if (record.status() == QuestRewardContract.JournalStatus.FROZEN) {
            tag.putString(FREEZE_ID_TAG, record.intent().key().canonical());
            tag.putString(FREEZE_TARGET_TAG, record.intent().targetPlayer().toString());
        }
        return tag;
    }

    static QuestRewardContract.JournalRecord readRecord(CompoundTag tag) {
        QuestRewardContract.Intent intent = readIntent(tag);
        String statusText = readString(tag, STATUS_TAG, 32);
        String reason = readString(tag, REASON_TAG, 256);
        if (intent == null || intent.key() == null || statusText == null || reason == null
                || !tag.contains(BALANCE_TAG, Tag.TAG_LONG)
                || !tag.contains(RECORDED_AT_TAG, Tag.TAG_LONG)) {
            return null;
        }

        QuestRewardContract.JournalStatus status;
        try {
            status = QuestRewardContract.JournalStatus.valueOf(statusText);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        UUID transactionId = readUuid(tag, TRANSACTION_TAG);
        if (status == QuestRewardContract.JournalStatus.APPLIED && transactionId == null) {
            return null;
        }
        long balance = tag.getLong(BALANCE_TAG);
        long recordedAt = tag.getLong(RECORDED_AT_TAG);
        if (balance < 0L || recordedAt <= 0L) {
            return null;
        }
        if (status == QuestRewardContract.JournalStatus.FROZEN) {
            String freezeId = readString(
                    tag,
                    FREEZE_ID_TAG,
                    QuestRewardContract.MAX_REWARD_KEY_LENGTH);
            String freezeTarget = readString(tag, FREEZE_TARGET_TAG, 64);
            if (freezeId == null
                    || !freezeId.equals(intent.key().canonical())
                    || freezeTarget == null
                    || !freezeTarget.equals(intent.targetPlayer().toString())) {
                return null;
            }
        }
        return new QuestRewardContract.JournalRecord(
                intent,
                status,
                transactionId,
                balance,
                recordedAt,
                reason);
    }

    static CompoundTag writeConflict(QuestRewardContract.ConflictRecord conflict) {
        CompoundTag tag = new CompoundTag();
        writeIntent(tag, conflict.incoming());
        tag.putString(EXISTING_KEY_TAG, conflict.existingKey());
        tag.putLong(RECORDED_AT_TAG, conflict.timestamp());
        tag.putString(REASON_TAG, conflict.reason());
        return tag;
    }

    static QuestRewardContract.ConflictRecord readConflict(CompoundTag tag) {
        QuestRewardContract.Intent incoming = readIntent(tag);
        String existingKey = readString(tag, EXISTING_KEY_TAG, 512);
        String reason = readString(tag, REASON_TAG, 256);
        if (incoming == null || incoming.key() == null || existingKey == null || reason == null
                || !tag.contains(RECORDED_AT_TAG, Tag.TAG_LONG)) {
            return null;
        }
        long timestamp = tag.getLong(RECORDED_AT_TAG);
        if (timestamp <= 0L) {
            return null;
        }
        return new QuestRewardContract.ConflictRecord(incoming, existingKey, timestamp, reason);
    }

    private static UUID readUuid(CompoundTag tag, String key) {
        String value = readString(tag, key, 64);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String readString(CompoundTag tag, String key, int maxLength) {
        if (tag == null || !tag.contains(key, Tag.TAG_STRING)) {
            return null;
        }
        String value = tag.getString(key);
        return value.length() <= maxLength ? value : null;
    }
}
