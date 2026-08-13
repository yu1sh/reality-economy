package io.github.yu1sh.reality.economy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Persistent server-owned Shop state, including all prior world epochs. */
final class ShopData extends SavedData {
    static final String DATA_NAME = "reality_economy_shop";

    private static final String CURRENT_EPOCH_TAG = "current_epoch";
    private static final String NAMESPACES_TAG = "namespaces";
    private static final String EPOCH_TAG = "epoch";
    private static final String REVISION_TAG = "revision";
    private static final String CLERKS_TAG = "clerks";
    private static final String ENTRIES_TAG = "entries";
    private static final String PURCHASES_TAG = "purchases";
    private static final String AUDITS_TAG = "audits";
    private static final String REQUESTS_TAG = "requests";

    private static final String ID_TAG = "id";
    private static final String ITEM_ID_TAG = "item_id";
    private static final String QUANTITY_TAG = "quantity";
    private static final String PRICE_TAG = "price";
    private static final String ACTIVE_TAG = "active";
    private static final String CREATED_REVISION_TAG = "created_revision";
    private static final String UPDATED_REVISION_TAG = "updated_revision";
    private static final String PURCHASE_ID_TAG = "purchase_id";
    private static final String REQUEST_ID_TAG = "request_id";
    private static final String BUYER_TAG = "buyer";
    private static final String CATALOG_REVISION_TAG = "catalog_revision";
    private static final String STATUS_TAG = "status";
    private static final String DELIVERY_CONFIRMED_TAG = "delivery_confirmed";
    private static final String TIMESTAMP_TAG = "timestamp";
    private static final String MESSAGE_TAG = "message";
    private static final String AUDIT_ID_TAG = "audit_id";
    private static final String ACTOR_TAG = "actor";
    private static final String TARGET_TAG = "target";
    private static final String ACTION_TAG = "action";
    private static final String BEFORE_ITEM_ID_TAG = "before_item_id";
    private static final String BEFORE_QUANTITY_TAG = "before_quantity";
    private static final String BEFORE_PRICE_TAG = "before_price";
    private static final String BEFORE_ACTIVE_TAG = "before_active";
    private static final String AFTER_ITEM_ID_TAG = "after_item_id";
    private static final String AFTER_QUANTITY_TAG = "after_quantity";
    private static final String AFTER_PRICE_TAG = "after_price";
    private static final String AFTER_ACTIVE_TAG = "after_active";
    private static final String EXPECTED_REVISION_TAG = "expected_revision";
    private static final String PURCHASE_ID_REF_TAG = "purchase_id_ref";
    private static final String RESULT_TAG = "result";
    private static final String REASON_TAG = "reason";
    private static final String OPERATION_TAG = "operation";
    private static final String APPLIED_TAG = "applied";
    private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

    private String currentEpoch;
    private final Map<String, Namespace> namespaces = new LinkedHashMap<>();
    private boolean recoveredOnLoad;

    private ShopData() {
    }

    static ShopData create() {
        ShopData data = new ShopData();
        String epoch = UUID.randomUUID().toString();
        data.currentEpoch = epoch;
        data.namespaces.put(epoch, Namespace.create(epoch));
        return data;
    }

    static ShopData load(CompoundTag tag) {
        ShopData data = new ShopData();
        ListTag namespaceTags = tag.getList(NAMESPACES_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < namespaceTags.size(); index++) {
            Namespace namespace = Namespace.load(namespaceTags.getCompound(index));
            if (namespace != null) {
                data.namespaces.put(namespace.epoch, namespace);
            }
        }

        String loadedEpoch = readString(tag, CURRENT_EPOCH_TAG, 64);
        if (data.namespaces.isEmpty()) {
            String epoch = UUID.randomUUID().toString();
            data.currentEpoch = epoch;
            data.namespaces.put(epoch, Namespace.create(epoch));
        } else if (loadedEpoch != null && data.namespaces.containsKey(loadedEpoch)) {
            data.currentEpoch = loadedEpoch;
        } else {
            data.currentEpoch = data.namespaces.keySet().iterator().next();
        }

        for (Namespace namespace : data.namespaces.values()) {
            for (Map.Entry<UUID, ShopDomain.PurchaseRecord> entry : namespace.purchases.entrySet()) {
                ShopDomain.PurchaseStatus status = entry.getValue().status();
                if (status == ShopDomain.PurchaseStatus.PENDING
                        || status == ShopDomain.PurchaseStatus.ITEM_DELIVERED) {
                    ShopDomain.PurchaseRecord purchase = withPurchaseStatus(
                            entry.getValue(),
                            ShopDomain.PurchaseStatus.RECOVERY_REQUIRED,
                            status == ShopDomain.PurchaseStatus.ITEM_DELIVERED
                                    ? "delivery confirmed before restart; debit recovery required"
                                    : "delivery unconfirmed after restart",
                            status == ShopDomain.PurchaseStatus.ITEM_DELIVERED);
                    namespace.purchases.put(entry.getKey(), purchase);
                    namespace.audits.add(new ShopDomain.AuditRecord(
                            UUID.randomUUID(),
                            System.currentTimeMillis(),
                            namespace.epoch,
                            ShopDomain.SHOP_ID,
                            SYSTEM_ACTOR,
                            purchase.buyer(),
                            "RECOVERY_REQUESTED",
                            purchase.entryId(),
                            purchase.itemId(),
                            purchase.quantity(),
                            purchase.price(),
                            true,
                            purchase.itemId(),
                            purchase.quantity(),
                            purchase.price(),
                            true,
                            purchase.catalogRevision(),
                            purchase.catalogRevision(),
                            purchase.requestId(),
                            purchase.purchaseId(),
                            "recovery_required",
                            "restart left purchase unresolved"));
                    namespace.recoveredOnLoad = true;
                    data.recoveredOnLoad = true;
                }
            }
        }
        return data;
    }

    static ShopData forLevel(ServerLevel level) {
        ShopData data = level.getServer().overworld().getDataStorage().computeIfAbsent(
                ShopData::load,
                ShopData::create,
                DATA_NAME);
        if (data.recoveredOnLoad) {
            data.recoveredOnLoad = false;
            data.setDirty();
            level.getServer().overworld().getDataStorage().save();
        }
        return data;
    }

    synchronized String currentEpoch() {
        return currentEpoch;
    }

    synchronized long currentRevision() {
        return currentNamespace().revision;
    }

    synchronized List<ShopDomain.CatalogEntry> entries(boolean includeInactive) {
        List<ShopDomain.CatalogEntry> entries = new ArrayList<>();
        for (ShopDomain.CatalogEntry entry : currentNamespace().entries.values()) {
            if (includeInactive || entry.active()) {
                entries.add(entry);
            }
        }
        entries.sort(Comparator.comparing(ShopDomain.CatalogEntry::id));
        return entries;
    }

    synchronized ShopDomain.CatalogEntry entry(String entryId) {
        return currentNamespace().entries.get(entryId);
    }

    synchronized boolean isClerk(UUID player) {
        return currentNamespace().clerks.contains(player);
    }

    synchronized Set<UUID> clerks() {
        return Set.copyOf(currentNamespace().clerks);
    }

    synchronized ShopDomain.RequestRecord request(UUID requestId) {
        return currentNamespace().requests.get(requestId);
    }

    synchronized ShopDomain.RequestRecord requestAny(UUID requestId) {
        for (Namespace namespace : namespaces.values()) {
            ShopDomain.RequestRecord request = namespace.requests.get(requestId);
            if (request != null) {
                return request;
            }
        }
        return null;
    }

    synchronized ShopDomain.PurchaseRecord purchaseByRequest(UUID requestId) {
        for (ShopDomain.PurchaseRecord purchase : currentNamespace().purchases.values()) {
            if (purchase.requestId().equals(requestId)) {
                return purchase;
            }
        }
        return null;
    }

    synchronized ShopDomain.PurchaseRecord purchaseByRequestAny(UUID requestId) {
        for (Namespace namespace : namespaces.values()) {
            for (ShopDomain.PurchaseRecord purchase : namespace.purchases.values()) {
                if (purchase.requestId().equals(requestId)) {
                    return purchase;
                }
            }
        }
        return null;
    }

    synchronized ShopDomain.PurchaseRecord purchase(UUID purchaseId) {
        return currentNamespace().purchases.get(purchaseId);
    }

    synchronized List<ShopDomain.PurchaseRecord> unresolvedPurchases() {
        List<ShopDomain.PurchaseRecord> unresolved = new ArrayList<>();
        for (ShopDomain.PurchaseRecord purchase : currentNamespace().purchases.values()) {
            if (purchase.status() == ShopDomain.PurchaseStatus.PENDING
                    || purchase.status() == ShopDomain.PurchaseStatus.ITEM_DELIVERED
                    || purchase.status() == ShopDomain.PurchaseStatus.RECOVERY_REQUIRED) {
                unresolved.add(purchase);
            }
        }
        return unresolved;
    }

    synchronized List<ShopDomain.PurchaseRecord> recoveryPurchases() {
        List<ShopDomain.PurchaseRecord> recoveries = new ArrayList<>();
        for (ShopDomain.PurchaseRecord purchase : currentNamespace().purchases.values()) {
            if (purchase.status() == ShopDomain.PurchaseStatus.RECOVERY_REQUIRED) {
                recoveries.add(purchase);
            }
        }
        recoveries.sort(Comparator.comparing(purchase -> purchase.purchaseId().toString()));
        return recoveries;
    }

    synchronized boolean recoveryBlockedFor(UUID buyer) {
        for (ShopDomain.PurchaseRecord purchase : unresolvedPurchases()) {
            if (purchase.buyer().equals(buyer)) {
                return true;
            }
        }
        return false;
    }

    synchronized boolean reconcile(Predicate<UUID> debitExists) {
        boolean changed = false;
        Namespace namespace = currentNamespace();
        for (Map.Entry<UUID, ShopDomain.PurchaseRecord> entry : namespace.purchases.entrySet()) {
            ShopDomain.PurchaseRecord purchase = entry.getValue();
            if (purchase.status() != ShopDomain.PurchaseStatus.PENDING
                    && purchase.status() != ShopDomain.PurchaseStatus.ITEM_DELIVERED) {
                continue;
            }

            ShopDomain.PurchaseStatus status;
            boolean deliveryConfirmed;
            String message;
            if (purchase.status() == ShopDomain.PurchaseStatus.PENDING) {
                status = ShopDomain.PurchaseStatus.RECOVERY_REQUIRED;
                deliveryConfirmed = false;
                message = debitExists.test(purchase.purchaseId())
                        ? "debit exists but delivery is unconfirmed after restart"
                        : "delivery unconfirmed after restart";
            } else if (debitExists.test(purchase.purchaseId())) {
                status = ShopDomain.PurchaseStatus.COMMITTED;
                deliveryConfirmed = true;
                message = "reconciled after restart";
            } else {
                status = ShopDomain.PurchaseStatus.RECOVERY_REQUIRED;
                deliveryConfirmed = true;
                message = "delivery confirmed but debit recovery is required";
            }
            entry.setValue(withPurchaseStatus(purchase, status, message, deliveryConfirmed));
            namespace.audits.add(new ShopDomain.AuditRecord(
                    UUID.randomUUID(),
                    System.currentTimeMillis(),
                    namespace.epoch,
                    ShopDomain.SHOP_ID,
                    SYSTEM_ACTOR,
                    purchase.buyer(),
                    "RECOVERY_RECONCILED",
                    purchase.entryId(),
                    purchase.itemId(),
                    purchase.quantity(),
                    purchase.price(),
                    true,
                    purchase.itemId(),
                    purchase.quantity(),
                    purchase.price(),
                    true,
                    namespace.revision,
                    namespace.revision,
                    purchase.requestId(),
                    purchase.purchaseId(),
                    status.name(),
                    message));
            changed = true;
        }
        if (changed) {
            setDirty();
        }
        return changed;
    }

    synchronized void addPurchase(ShopDomain.PurchaseRecord purchase) {
        currentNamespace().purchases.put(purchase.purchaseId(), purchase);
        setDirty();
    }

    synchronized ShopDomain.PurchaseRecord updatePurchase(
            UUID purchaseId,
            ShopDomain.PurchaseStatus status,
            String message) {
        ShopDomain.PurchaseRecord current = currentNamespace().purchases.get(purchaseId);
        if (current == null) {
            return null;
        }
        return updatePurchase(purchaseId, status, message, current.deliveryConfirmed());
    }

    synchronized ShopDomain.PurchaseRecord updatePurchase(
            UUID purchaseId,
            ShopDomain.PurchaseStatus status,
            String message,
            boolean deliveryConfirmed) {
        ShopDomain.PurchaseRecord current = currentNamespace().purchases.get(purchaseId);
        if (current == null) {
            return null;
        }
        ShopDomain.PurchaseRecord updated = withPurchaseStatus(current, status, message, deliveryConfirmed);
        currentNamespace().purchases.put(purchaseId, updated);
        setDirty();
        return updated;
    }

    synchronized void recordRequest(ShopDomain.RequestRecord request) {
        currentNamespace().requests.put(request.requestId(), request);
        setDirty();
    }

    synchronized void appendAudit(ShopDomain.AuditRecord audit) {
        currentNamespace().audits.add(audit);
        setDirty();
    }

    synchronized CatalogMutationResult addEntry(
            String entryId,
            String itemId,
            int quantity,
            long price) {
        Namespace namespace = currentNamespace();
        if (namespace.entries.containsKey(entryId)) {
            return CatalogMutationResult.failure("entry already exists", namespace.revision);
        }
        if (namespace.entries.size() >= ShopDomain.RETAINED_ENTRY_LIMIT) {
            return CatalogMutationResult.failure("retained entry limit reached", namespace.revision);
        }
        if (activeCount(namespace) >= ShopDomain.ACTIVE_ENTRY_LIMIT) {
            return CatalogMutationResult.failure("active entry limit reached", namespace.revision);
        }

        long revision = nextRevision(namespace);
        ShopDomain.CatalogEntry after = new ShopDomain.CatalogEntry(
                entryId, itemId, quantity, price, true, revision, revision);
        namespace.entries.put(entryId, after);
        setDirty();
        return CatalogMutationResult.success(null, after, revision);
    }

    synchronized CatalogMutationResult changeEntry(
            String entryId,
            String itemId,
            int quantity,
            long price) {
        Namespace namespace = currentNamespace();
        ShopDomain.CatalogEntry before = namespace.entries.get(entryId);
        if (before == null) {
            return CatalogMutationResult.failure("entry not found", namespace.revision);
        }

        long revision = nextRevision(namespace);
        ShopDomain.CatalogEntry after = new ShopDomain.CatalogEntry(
                entryId, itemId, quantity, price, before.active(), before.createdRevision(), revision);
        namespace.entries.put(entryId, after);
        setDirty();
        return CatalogMutationResult.success(before, after, revision);
    }

    synchronized CatalogMutationResult stopEntry(String entryId) {
        Namespace namespace = currentNamespace();
        ShopDomain.CatalogEntry before = namespace.entries.get(entryId);
        if (before == null) {
            return CatalogMutationResult.failure("entry not found", namespace.revision);
        }
        if (!before.active()) {
            return CatalogMutationResult.failure("entry is already stopped", namespace.revision);
        }

        long revision = nextRevision(namespace);
        ShopDomain.CatalogEntry after = new ShopDomain.CatalogEntry(
                entryId,
                before.itemId(),
                before.quantity(),
                before.price(),
                false,
                before.createdRevision(),
                revision);
        namespace.entries.put(entryId, after);
        setDirty();
        return CatalogMutationResult.success(before, after, revision);
    }

    synchronized CatalogMutationResult resumeEntry(String entryId) {
        Namespace namespace = currentNamespace();
        ShopDomain.CatalogEntry before = namespace.entries.get(entryId);
        if (before == null) {
            return CatalogMutationResult.failure("entry not found", namespace.revision);
        }
        if (before.active()) {
            return CatalogMutationResult.failure("entry is already active", namespace.revision);
        }
        if (activeCount(namespace) >= ShopDomain.ACTIVE_ENTRY_LIMIT) {
            return CatalogMutationResult.failure("active entry limit reached", namespace.revision);
        }

        long revision = nextRevision(namespace);
        ShopDomain.CatalogEntry after = new ShopDomain.CatalogEntry(
                entryId,
                before.itemId(),
                before.quantity(),
                before.price(),
                true,
                before.createdRevision(),
                revision);
        namespace.entries.put(entryId, after);
        setDirty();
        return CatalogMutationResult.success(before, after, revision);
    }

    synchronized SimpleResult appoint(UUID target) {
        Namespace namespace = currentNamespace();
        if (namespace.clerks.contains(target)) {
            return SimpleResult.failure("player is already a clerk");
        }
        namespace.clerks.add(target);
        setDirty();
        return SimpleResult.success("clerk appointed");
    }

    synchronized SimpleResult revoke(UUID target) {
        Namespace namespace = currentNamespace();
        if (!namespace.clerks.remove(target)) {
            return SimpleResult.failure("player is not a clerk");
        }
        setDirty();
        return SimpleResult.success("clerk revoked");
    }

    synchronized ResetResult reset(UUID actor, UUID requestId, long timestamp) {
        String oldEpoch = currentEpoch;
        Namespace oldNamespace = currentNamespace();
        oldNamespace.audits.add(new ShopDomain.AuditRecord(
                UUID.randomUUID(),
                timestamp,
                oldEpoch,
                ShopDomain.SHOP_ID,
                actor,
                null,
                "WORLD_RESET_STARTED",
                "",
                "",
                0,
                0L,
                false,
                "",
                0,
                0L,
                false,
                oldNamespace.revision,
                oldNamespace.revision,
                requestId,
                null,
                "accepted",
                "world reset"));

        String newEpoch = UUID.randomUUID().toString();
        Namespace newNamespace = Namespace.create(newEpoch);
        newNamespace.audits.add(new ShopDomain.AuditRecord(
                UUID.randomUUID(),
                timestamp,
                newEpoch,
                ShopDomain.SHOP_ID,
                actor,
                null,
                "WORLD_RESET_COMPLETED",
                "",
                "",
                0,
                0L,
                false,
                "",
                0,
                0L,
                false,
                newNamespace.revision,
                newNamespace.revision,
                requestId,
                null,
                "applied",
                "new economy and catalog epoch"));
        namespaces.put(newEpoch, newNamespace);
        currentEpoch = newEpoch;
        setDirty();
        return new ResetResult(oldEpoch, newEpoch, newNamespace.revision);
    }

    private Namespace currentNamespace() {
        return namespaces.get(currentEpoch);
    }

    private static long nextRevision(Namespace namespace) {
        if (namespace.revision == Long.MAX_VALUE) {
            return namespace.revision;
        }
        namespace.revision++;
        return namespace.revision;
    }

    private static int activeCount(Namespace namespace) {
        int active = 0;
        for (ShopDomain.CatalogEntry entry : namespace.entries.values()) {
            if (entry.active()) {
                active++;
            }
        }
        return active;
    }

    private static ShopDomain.PurchaseRecord withPurchaseStatus(
            ShopDomain.PurchaseRecord purchase,
            ShopDomain.PurchaseStatus status,
            String message,
            boolean deliveryConfirmed) {
        return new ShopDomain.PurchaseRecord(
                purchase.purchaseId(),
                purchase.requestId(),
                purchase.buyer(),
                purchase.epoch(),
                purchase.shopId(),
                purchase.entryId(),
                purchase.itemId(),
                purchase.quantity(),
                purchase.price(),
                purchase.catalogRevision(),
                status,
                deliveryConfirmed,
                purchase.timestamp(),
                message);
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag) {
        tag.putString(CURRENT_EPOCH_TAG, currentEpoch);
        ListTag namespaceTags = new ListTag();
        List<Namespace> orderedNamespaces = new ArrayList<>(namespaces.values());
        orderedNamespaces.sort(Comparator.comparing(namespace -> namespace.epoch));
        for (Namespace namespace : orderedNamespaces) {
            namespaceTags.add(namespace.save());
        }
        tag.put(NAMESPACES_TAG, namespaceTags);
        return tag;
    }

    record CatalogMutationResult(
            boolean applied,
            String message,
            ShopDomain.CatalogEntry before,
            ShopDomain.CatalogEntry after,
            long revision) {
        static CatalogMutationResult success(
                ShopDomain.CatalogEntry before,
                ShopDomain.CatalogEntry after,
                long revision) {
            return new CatalogMutationResult(true, "applied", before, after, revision);
        }

        static CatalogMutationResult failure(String message, long revision) {
            return new CatalogMutationResult(false, message, null, null, revision);
        }
    }

    record SimpleResult(boolean applied, String message) {
        static SimpleResult success(String message) {
            return new SimpleResult(true, message);
        }

        static SimpleResult failure(String message) {
            return new SimpleResult(false, message);
        }
    }

    record ResetResult(String oldEpoch, String newEpoch, long revision) {
    }

    private static final class Namespace {
        private final String epoch;
        private long revision;
        private final Map<String, ShopDomain.CatalogEntry> entries = new LinkedHashMap<>();
        private final Set<UUID> clerks = new LinkedHashSet<>();
        private final Map<UUID, ShopDomain.PurchaseRecord> purchases = new LinkedHashMap<>();
        private final List<ShopDomain.AuditRecord> audits = new ArrayList<>();
        private final Map<UUID, ShopDomain.RequestRecord> requests = new HashMap<>();
        private boolean recoveredOnLoad;

        private Namespace(String epoch, long revision) {
            this.epoch = epoch;
            this.revision = revision;
        }

        private static Namespace create(String epoch) {
            Namespace namespace = new Namespace(epoch, 1L);
            for (ShopDomain.CatalogEntry entry : ShopDomain.initialCatalog(1L)) {
                namespace.entries.put(entry.id(), entry);
            }
            return namespace;
        }

        private static Namespace load(CompoundTag tag) {
            String epoch = readString(tag, EPOCH_TAG, 64);
            if (epoch == null) {
                return null;
            }
            long revision = tag.contains(REVISION_TAG, Tag.TAG_LONG) ? tag.getLong(REVISION_TAG) : 1L;
            if (revision < 1L) {
                revision = 1L;
            }
            Namespace namespace = new Namespace(epoch, revision);

            ListTag entryTags = tag.getList(ENTRIES_TAG, Tag.TAG_COMPOUND);
            for (int index = 0; index < entryTags.size() && namespace.entries.size() < ShopDomain.RETAINED_ENTRY_LIMIT; index++) {
                CompoundTag entryTag = entryTags.getCompound(index);
                String id = readString(entryTag, ID_TAG, ShopDomain.MAX_ID_LENGTH);
                String itemId = readString(entryTag, ITEM_ID_TAG, ShopDomain.MAX_ITEM_ID_LENGTH);
                int quantity = entryTag.getInt(QUANTITY_TAG);
                long price = entryTag.getLong(PRICE_TAG);
                if (!ShopDomain.validId(id, ShopDomain.MAX_ID_LENGTH)
                        || itemId == null
                        || quantity < 1
                        || quantity > ShopDomain.MAX_QUANTITY
                        || price < ShopDomain.MIN_PRICE
                        || price > ShopDomain.MAX_PRICE) {
                    continue;
                }
                long createdRevision = entryTag.contains(CREATED_REVISION_TAG, Tag.TAG_LONG)
                        ? entryTag.getLong(CREATED_REVISION_TAG)
                        : 1L;
                long updatedRevision = entryTag.contains(UPDATED_REVISION_TAG, Tag.TAG_LONG)
                        ? entryTag.getLong(UPDATED_REVISION_TAG)
                        : createdRevision;
                boolean active = entryTag.getBoolean(ACTIVE_TAG);
                if (active && activeCount(namespace) >= ShopDomain.ACTIVE_ENTRY_LIMIT) {
                    active = false;
                }
                namespace.entries.put(
                        id,
                        new ShopDomain.CatalogEntry(
                                id,
                                itemId,
                                quantity,
                                price,
                                active,
                                Math.max(1L, createdRevision),
                                Math.max(1L, updatedRevision)));
            }

            ListTag clerkTags = tag.getList(CLERKS_TAG, Tag.TAG_STRING);
            for (int index = 0; index < clerkTags.size(); index++) {
                UUID clerk = parseUuid(clerkTags.getString(index));
                if (clerk != null) {
                    namespace.clerks.add(clerk);
                }
            }

            ListTag purchaseTags = tag.getList(PURCHASES_TAG, Tag.TAG_COMPOUND);
            for (int index = 0; index < purchaseTags.size(); index++) {
                ShopDomain.PurchaseRecord purchase = readPurchase(purchaseTags.getCompound(index), epoch);
                if (purchase != null) {
                    namespace.purchases.put(purchase.purchaseId(), purchase);
                }
            }

            ListTag requestTags = tag.getList(REQUESTS_TAG, Tag.TAG_COMPOUND);
            for (int index = 0; index < requestTags.size(); index++) {
                ShopDomain.RequestRecord request = readRequest(requestTags.getCompound(index), epoch);
                if (request != null) {
                    namespace.requests.put(request.requestId(), request);
                }
            }

            ListTag auditTags = tag.getList(AUDITS_TAG, Tag.TAG_COMPOUND);
            for (int index = 0; index < auditTags.size(); index++) {
                ShopDomain.AuditRecord audit = readAudit(auditTags.getCompound(index), epoch);
                if (audit != null) {
                    namespace.audits.add(audit);
                }
            }
            return namespace;
        }

        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString(EPOCH_TAG, epoch);
            tag.putLong(REVISION_TAG, revision);

            ListTag clerkTags = new ListTag();
            List<UUID> orderedClerks = new ArrayList<>(clerks);
            orderedClerks.sort(Comparator.comparing(UUID::toString));
            for (UUID clerk : orderedClerks) {
                clerkTags.add(net.minecraft.nbt.StringTag.valueOf(clerk.toString()));
            }
            tag.put(CLERKS_TAG, clerkTags);

            ListTag entryTags = new ListTag();
            List<ShopDomain.CatalogEntry> orderedEntries = new ArrayList<>(entries.values());
            orderedEntries.sort(Comparator.comparing(ShopDomain.CatalogEntry::id));
            for (ShopDomain.CatalogEntry entry : orderedEntries) {
                CompoundTag entryTag = new CompoundTag();
                entryTag.putString(ID_TAG, entry.id());
                entryTag.putString(ITEM_ID_TAG, entry.itemId());
                entryTag.putInt(QUANTITY_TAG, entry.quantity());
                entryTag.putLong(PRICE_TAG, entry.price());
                entryTag.putBoolean(ACTIVE_TAG, entry.active());
                entryTag.putLong(CREATED_REVISION_TAG, entry.createdRevision());
                entryTag.putLong(UPDATED_REVISION_TAG, entry.updatedRevision());
                entryTags.add(entryTag);
            }
            tag.put(ENTRIES_TAG, entryTags);

            ListTag purchaseTags = new ListTag();
            List<ShopDomain.PurchaseRecord> orderedPurchases = new ArrayList<>(purchases.values());
            orderedPurchases.sort(Comparator.comparing(purchase -> purchase.purchaseId().toString()));
            for (ShopDomain.PurchaseRecord purchase : orderedPurchases) {
                purchaseTags.add(writePurchase(purchase));
            }
            tag.put(PURCHASES_TAG, purchaseTags);

            ListTag requestTags = new ListTag();
            List<ShopDomain.RequestRecord> orderedRequests = new ArrayList<>(requests.values());
            orderedRequests.sort(Comparator.comparing(request -> request.requestId().toString()));
            for (ShopDomain.RequestRecord request : orderedRequests) {
                requestTags.add(writeRequest(request));
            }
            tag.put(REQUESTS_TAG, requestTags);

            ListTag auditTags = new ListTag();
            for (ShopDomain.AuditRecord audit : audits) {
                auditTags.add(writeAudit(audit));
            }
            tag.put(AUDITS_TAG, auditTags);
            return tag;
        }
    }

    private static CompoundTag writePurchase(ShopDomain.PurchaseRecord purchase) {
        CompoundTag tag = new CompoundTag();
        tag.putString(PURCHASE_ID_TAG, purchase.purchaseId().toString());
        tag.putString(REQUEST_ID_TAG, purchase.requestId().toString());
        tag.putString(BUYER_TAG, purchase.buyer().toString());
        tag.putString(ID_TAG, purchase.entryId());
        tag.putString(ITEM_ID_TAG, purchase.itemId());
        tag.putInt(QUANTITY_TAG, purchase.quantity());
        tag.putLong(PRICE_TAG, purchase.price());
        tag.putLong(CATALOG_REVISION_TAG, purchase.catalogRevision());
        tag.putString(STATUS_TAG, purchase.status().name());
        tag.putBoolean(DELIVERY_CONFIRMED_TAG, purchase.deliveryConfirmed());
        tag.putLong(TIMESTAMP_TAG, purchase.timestamp());
        tag.putString(MESSAGE_TAG, purchase.message());
        return tag;
    }

    private static ShopDomain.PurchaseRecord readPurchase(CompoundTag tag, String epoch) {
        UUID purchaseId = readUuid(tag, PURCHASE_ID_TAG);
        UUID requestId = readUuid(tag, REQUEST_ID_TAG);
        UUID buyer = readUuid(tag, BUYER_TAG);
        String entryId = readString(tag, ID_TAG, ShopDomain.MAX_ID_LENGTH);
        String itemId = readString(tag, ITEM_ID_TAG, ShopDomain.MAX_ITEM_ID_LENGTH);
        String statusText = readString(tag, STATUS_TAG, 32);
        String message = readString(tag, MESSAGE_TAG, 256);
        if (purchaseId == null || requestId == null || buyer == null || entryId == null || itemId == null
                || statusText == null || message == null) {
            return null;
        }
        ShopDomain.PurchaseStatus status;
        try {
            status = ShopDomain.PurchaseStatus.valueOf(statusText);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        int quantity = tag.getInt(QUANTITY_TAG);
        long price = tag.getLong(PRICE_TAG);
        long revision = tag.getLong(CATALOG_REVISION_TAG);
        if (quantity < 1 || quantity > ShopDomain.MAX_QUANTITY || price < ShopDomain.MIN_PRICE
                || price > ShopDomain.MAX_PRICE || revision < 1L) {
            return null;
        }
        boolean deliveryConfirmed = tag.getBoolean(DELIVERY_CONFIRMED_TAG)
                || status == ShopDomain.PurchaseStatus.ITEM_DELIVERED
                || legacyDeliveryConfirmed(status, message);
        return new ShopDomain.PurchaseRecord(
                purchaseId,
                requestId,
                buyer,
                epoch,
                ShopDomain.SHOP_ID,
                entryId,
                itemId,
                quantity,
                price,
                revision,
                status,
                deliveryConfirmed,
                tag.getLong(TIMESTAMP_TAG),
                message);
    }

    private static boolean legacyDeliveryConfirmed(ShopDomain.PurchaseStatus status, String message) {
        return status == ShopDomain.PurchaseStatus.RECOVERY_REQUIRED
                && ("debit transaction conflict".equals(message)
                        || "debit could not be applied".equals(message));
    }

    private static CompoundTag writeRequest(ShopDomain.RequestRecord request) {
        CompoundTag tag = new CompoundTag();
        tag.putString(REQUEST_ID_TAG, request.requestId().toString());
        tag.putString(ACTOR_TAG, request.actor().toString());
        tag.putString(EPOCH_TAG, request.epoch());
        tag.putString(OPERATION_TAG, request.operation().name());
        tag.putBoolean(APPLIED_TAG, request.applied());
        tag.putLong(REVISION_TAG, request.revision());
        if (request.purchaseId() != null) {
            tag.putString(PURCHASE_ID_REF_TAG, request.purchaseId().toString());
        }
        tag.putLong(TIMESTAMP_TAG, request.timestamp());
        tag.putString(MESSAGE_TAG, request.message());
        return tag;
    }

    private static ShopDomain.RequestRecord readRequest(CompoundTag tag, String epoch) {
        UUID requestId = readUuid(tag, REQUEST_ID_TAG);
        UUID actor = readUuid(tag, ACTOR_TAG);
        String requestEpoch = readString(tag, EPOCH_TAG, 64);
        String operationText = readString(tag, OPERATION_TAG, 32);
        String message = readString(tag, MESSAGE_TAG, 256);
        if (requestId == null || actor == null || requestEpoch == null || message == null
                || !epoch.equals(requestEpoch) || operationText == null) {
            return null;
        }
        ShopDomain.Operation operation;
        try {
            operation = ShopDomain.Operation.valueOf(operationText);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        UUID purchaseId = readUuid(tag, PURCHASE_ID_REF_TAG);
        return new ShopDomain.RequestRecord(
                requestId,
                actor,
                epoch,
                ShopDomain.SHOP_ID,
                operation,
                tag.getBoolean(APPLIED_TAG),
                Math.max(1L, tag.getLong(REVISION_TAG)),
                purchaseId,
                tag.getLong(TIMESTAMP_TAG),
                message);
    }

    private static CompoundTag writeAudit(ShopDomain.AuditRecord audit) {
        CompoundTag tag = new CompoundTag();
        tag.putString(AUDIT_ID_TAG, audit.auditId().toString());
        tag.putLong(TIMESTAMP_TAG, audit.timestamp());
        tag.putString(EPOCH_TAG, audit.epoch());
        tag.putString(ACTOR_TAG, audit.actor().toString());
        putUuid(tag, TARGET_TAG, audit.target());
        tag.putString(ACTION_TAG, audit.action());
        tag.putString(ID_TAG, audit.entryId());
        tag.putString(BEFORE_ITEM_ID_TAG, audit.beforeItemId());
        tag.putInt(BEFORE_QUANTITY_TAG, audit.beforeQuantity());
        tag.putLong(BEFORE_PRICE_TAG, audit.beforePrice());
        tag.putBoolean(BEFORE_ACTIVE_TAG, audit.beforeActive());
        tag.putString(AFTER_ITEM_ID_TAG, audit.afterItemId());
        tag.putInt(AFTER_QUANTITY_TAG, audit.afterQuantity());
        tag.putLong(AFTER_PRICE_TAG, audit.afterPrice());
        tag.putBoolean(AFTER_ACTIVE_TAG, audit.afterActive());
        tag.putLong(REVISION_TAG, audit.revision());
        tag.putLong(EXPECTED_REVISION_TAG, audit.expectedRevision());
        putUuid(tag, REQUEST_ID_TAG, audit.requestId());
        putUuid(tag, PURCHASE_ID_REF_TAG, audit.purchaseId());
        tag.putString(RESULT_TAG, audit.result());
        tag.putString(REASON_TAG, audit.reason());
        return tag;
    }

    private static ShopDomain.AuditRecord readAudit(CompoundTag tag, String epoch) {
        UUID auditId = readUuid(tag, AUDIT_ID_TAG);
        UUID actor = readUuid(tag, ACTOR_TAG);
        String auditEpoch = readString(tag, EPOCH_TAG, 64);
        String action = readString(tag, ACTION_TAG, 64);
        String entryId = readString(tag, ID_TAG, ShopDomain.MAX_ID_LENGTH);
        String beforeItemId = readString(tag, BEFORE_ITEM_ID_TAG, ShopDomain.MAX_ITEM_ID_LENGTH);
        String afterItemId = readString(tag, AFTER_ITEM_ID_TAG, ShopDomain.MAX_ITEM_ID_LENGTH);
        String result = readString(tag, RESULT_TAG, 64);
        String reason = readString(tag, REASON_TAG, 256);
        if (auditId == null || actor == null || auditEpoch == null || !epoch.equals(auditEpoch)
                || action == null || entryId == null || beforeItemId == null || afterItemId == null
                || result == null || reason == null) {
            return null;
        }
        return new ShopDomain.AuditRecord(
                auditId,
                tag.getLong(TIMESTAMP_TAG),
                epoch,
                ShopDomain.SHOP_ID,
                actor,
                readUuid(tag, TARGET_TAG),
                action,
                entryId,
                beforeItemId,
                tag.getInt(BEFORE_QUANTITY_TAG),
                tag.getLong(BEFORE_PRICE_TAG),
                tag.getBoolean(BEFORE_ACTIVE_TAG),
                afterItemId,
                tag.getInt(AFTER_QUANTITY_TAG),
                tag.getLong(AFTER_PRICE_TAG),
                tag.getBoolean(AFTER_ACTIVE_TAG),
                tag.getLong(REVISION_TAG),
                tag.getLong(EXPECTED_REVISION_TAG),
                readUuid(tag, REQUEST_ID_TAG),
                readUuid(tag, PURCHASE_ID_REF_TAG),
                result,
                reason);
    }

    private static void putUuid(CompoundTag tag, String key, UUID value) {
        if (value != null) {
            tag.putString(key, value.toString());
        }
    }

    private static UUID readUuid(CompoundTag tag, String key) {
        String value = readString(tag, key, 64);
        return value == null ? null : parseUuid(value);
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String readString(CompoundTag tag, String key, int maxLength) {
        if (!tag.contains(key, Tag.TAG_STRING)) {
            return null;
        }
        String value = tag.getString(key);
        return value.length() <= maxLength ? value : null;
    }
}
