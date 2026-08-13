package io.github.yu1sh.reality.economy;

import java.util.List;
import java.util.UUID;

/** Minecraft-independent Shop v1 state and contract values. */
public final class ShopDomain {
    public static final String SHOP_ID = "default";
    public static final int PROTOCOL_VERSION = 2;
    public static final int ACTIVE_ENTRY_LIMIT = 16;
    public static final int RETAINED_ENTRY_LIMIT = 256;
    public static final int MAX_QUANTITY = 64;
    public static final long MIN_PRICE = 1L;
    public static final long MAX_PRICE = 100_000L;
    public static final int MAX_PAGE_SIZE = 16;
    public static final int MAX_ID_LENGTH = 64;
    public static final int MAX_ITEM_ID_LENGTH = 128;
    public static final int MAX_PLAYER_NAME_LENGTH = 64;
    public static final int MAX_RECOVERY_VIEWS = 64;

    private static final String BREAD_ENTRY = "bundle_a_food_bread";
    private static final String TORCH_ENTRY = "bundle_a_light_torch";
    private static final String OAK_PLANKS_ENTRY = "bundle_a_build_oak_planks";
    private static final String COOKED_BEEF_ENTRY = "bundle_b_food_cooked_beef";
    private static final String TORCH_TRAVEL_ENTRY = "bundle_b_light_torch";
    private static final String COBBLESTONE_ENTRY = "bundle_b_build_cobblestone";

    private ShopDomain() {
    }

    static List<CatalogEntry> initialCatalog(long revision) {
        return List.of(
                new CatalogEntry(BREAD_ENTRY, "minecraft:bread", 16, 12L, true, revision, revision),
                new CatalogEntry(TORCH_ENTRY, "minecraft:torch", 32, 8L, true, revision, revision),
                new CatalogEntry(OAK_PLANKS_ENTRY, "minecraft:oak_planks", 32, 10L, true, revision, revision),
                new CatalogEntry(COOKED_BEEF_ENTRY, "minecraft:cooked_beef", 8, 14L, true, revision, revision),
                new CatalogEntry(TORCH_TRAVEL_ENTRY, "minecraft:torch", 16, 6L, true, revision, revision),
                new CatalogEntry(COBBLESTONE_ENTRY, "minecraft:cobblestone", 64, 18L, true, revision, revision));
    }

    static boolean validId(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
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

    public enum Operation {
        LIST(0),
        DETAIL(1),
        PURCHASE(2),
        ADD(3),
        CHANGE(4),
        STOP(5),
        RESUME(6),
        APPOINT(7),
        REVOKE(8),
        RESET(9),
        RECOVERY_STATUS(10),
        RECOVERY_RETRY(11),
        RECOVERY_RESOLVE(12);

        private final int code;

        Operation(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        static Operation fromCode(int code) {
            for (Operation operation : values()) {
                if (operation.code == code) {
                    return operation;
                }
            }
            return null;
        }
    }

    enum PurchaseStatus {
        PENDING,
        ITEM_DELIVERED,
        COMMITTED,
        FAILED,
        RECOVERY_REQUIRED
    }

    record CatalogEntry(
            String id,
            String itemId,
            int quantity,
            long price,
            boolean active,
            long createdRevision,
            long updatedRevision) {
    }

    record PurchaseRecord(
            UUID purchaseId,
            UUID requestId,
            UUID buyer,
            String epoch,
            String shopId,
            String entryId,
            String itemId,
            int quantity,
            long price,
            long catalogRevision,
            PurchaseStatus status,
            boolean deliveryConfirmed,
            long timestamp,
            String message) {
    }

    record AuditRecord(
            UUID auditId,
            long timestamp,
            String epoch,
            String shopId,
            UUID actor,
            UUID target,
            String action,
            String entryId,
            String beforeItemId,
            int beforeQuantity,
            long beforePrice,
            boolean beforeActive,
            String afterItemId,
            int afterQuantity,
            long afterPrice,
            boolean afterActive,
            long revision,
            long expectedRevision,
            UUID requestId,
            UUID purchaseId,
            String result,
            String reason) {
    }

    record RequestRecord(
            UUID requestId,
            UUID actor,
            String epoch,
            String shopId,
            Operation operation,
            boolean applied,
            long revision,
            UUID purchaseId,
            long timestamp,
            String message) {
    }
}
