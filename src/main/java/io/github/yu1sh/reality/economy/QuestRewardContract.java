package io.github.yu1sh.reality.economy;

import java.util.UUID;

/**
 * Minecraft-independent values and invariants for the P-05 to P-07 reward
 * boundary. P-07 validates the received amount; it never derives an amount
 * from the quest identity.
 */
public final class QuestRewardContract {
    public static final int CONTRACT_MAJOR = 1;
    public static final String SOURCE = "P-05-QUESTS";
    public static final String CURRENCY_UNIT = "reality_economy";
    public static final String PRODUCER_MOD_ID = "reality_quests";
    /**
     * The IMC endpoint version is independent from the stable reward-key
     * contract major. Version 2 adds the terminal FROZEN result and must be
     * negotiated atomically by the producer and receiver.
     */
    public static final int REWARD_ENDPOINT_VERSION = 2;
    public static final String IMC_RECEIVE_METHOD = "quest_reward_receive_v2";
    public static final String IMC_SCOPE_METHOD = "quest_reward_scope_v2";
    public static final long MIN_REWARD_AMOUNT = 1L;
    public static final long MAX_REWARD_AMOUNT = 100_000L;

    static final int MAX_SOURCE_LENGTH = 64;
    static final int MAX_CURRENCY_LENGTH = 64;
    static final int MAX_WORLD_KEY_LENGTH = 128;
    static final int MAX_WORLD_EPOCH_LENGTH = 128;
    static final int MAX_QUEST_ID_LENGTH = 64;
    static final int MAX_COMPLETION_ID_LENGTH = 128;
    static final int MAX_DIMENSION_LENGTH = 128;
    static final int MAX_REWARD_KEY_LENGTH = 768;
    private static final char KEY_SEPARATOR = '\u001f';

    private QuestRewardContract() {
    }

    public enum ResultStatus {
        APPLIED,
        ALREADY_APPLIED,
        PENDING,
        REJECTED,
        CONFLICT,
        FROZEN
    }

    enum JournalStatus {
        PENDING,
        APPLIED,
        REJECTED,
        CONFLICT,
        FROZEN
    }

    public record WorldScope(String worldKey, String worldEpoch) {
    }

    /** Immutable server-to-server reward payload. */
    public record Intent(
            int contractMajor,
            String source,
            String currencyUnit,
            String worldKey,
            String worldEpoch,
            UUID targetPlayer,
            String questId,
            long definitionRevision,
            String completionId,
            long rewardAmount,
            String sourceDimension,
            long completionTimestamp,
            long intentTimestamp) {

        public Key key() {
            if (contractMajor <= 0
                    || !validText(source, MAX_SOURCE_LENGTH)
                    || !validText(currencyUnit, MAX_CURRENCY_LENGTH)
                    || !validText(worldKey, MAX_WORLD_KEY_LENGTH)
                    || !validText(worldEpoch, MAX_WORLD_EPOCH_LENGTH)
                    || targetPlayer == null
                    || !validIdentifier(questId, MAX_QUEST_ID_LENGTH)
                    || !validText(completionId, MAX_COMPLETION_ID_LENGTH)
                    || !validText(sourceDimension, MAX_DIMENSION_LENGTH)) {
                return null;
            }
            return new Key(
                    contractMajor,
                    source,
                    worldKey,
                    worldEpoch,
                    targetPlayer,
                    questId,
                    completionId);
        }

        public boolean samePayload(Intent other) {
            return equals(other);
        }
    }

    /** The idempotency identity; amount and definition metadata are not key fields. */
    public record Key(
            int contractMajor,
            String source,
            String worldKey,
            String worldEpoch,
            UUID targetPlayer,
            String questId,
            String completionId) {

        public String canonical() {
            return contractMajor
                    + String.valueOf(KEY_SEPARATOR) + source
                    + KEY_SEPARATOR + worldKey
                    + KEY_SEPARATOR + worldEpoch
                    + KEY_SEPARATOR + targetPlayer
                    + KEY_SEPARATOR + questId
                    + KEY_SEPARATOR + completionId;
        }
    }

    record JournalRecord(
            Intent intent,
            JournalStatus status,
            UUID transactionId,
            long balance,
            long recordedAt,
            String reason) {
    }

    record ConflictRecord(Intent incoming, String existingKey, long timestamp, String reason) {
    }

    public record Result(
            ResultStatus status,
            String message,
            long balance,
            UUID transactionId,
            String key) {
    }

    static String validate(Intent intent, WorldScope expectedScope) {
        if (intent == null) {
            return "intent is missing";
        }
        if (intent.contractMajor() != CONTRACT_MAJOR) {
            return "unsupported contract version";
        }
        if (!SOURCE.equals(intent.source())) {
            return "reward source rejected";
        }
        if (!CURRENCY_UNIT.equals(intent.currencyUnit())) {
            return "currency unit rejected";
        }
        if (expectedScope == null
                || !validText(expectedScope.worldKey(), MAX_WORLD_KEY_LENGTH)
                || !validText(expectedScope.worldEpoch(), MAX_WORLD_EPOCH_LENGTH)) {
            return "server world scope is unavailable";
        }
        if (!expectedScope.worldKey().equals(intent.worldKey())
                || !expectedScope.worldEpoch().equals(intent.worldEpoch())) {
            return "world scope or epoch rejected";
        }
        if (intent.targetPlayer() == null) {
            return "target player identity is missing";
        }
        if (!validIdentifier(intent.questId(), MAX_QUEST_ID_LENGTH)) {
            return "quest identity rejected";
        }
        if (intent.definitionRevision() < 1L) {
            return "definition revision rejected";
        }
        if (!validText(intent.completionId(), MAX_COMPLETION_ID_LENGTH)) {
            return "completion identity rejected";
        }
        if (intent.rewardAmount() < MIN_REWARD_AMOUNT
                || intent.rewardAmount() > MAX_REWARD_AMOUNT) {
            return "reward amount outside the accepted range";
        }
        if (!validText(intent.sourceDimension(), MAX_DIMENSION_LENGTH)) {
            return "source dimension rejected";
        }
        if (intent.completionTimestamp() <= 0L || intent.intentTimestamp() <= 0L) {
            return "server timestamps are missing";
        }
        return "";
    }

    static boolean validText(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || value.indexOf(KEY_SEPARATOR) >= 0) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean validIdentifier(String value, int maxLength) {
        if (!validText(value, maxLength)) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(Character.isLetterOrDigit(character)
                    || character == '_'
                    || character == '-'
                    || character == '.')) {
                return false;
            }
        }
        return true;
    }
}
