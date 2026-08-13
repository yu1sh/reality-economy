package io.github.yu1sh.reality.economy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Server-side Shop application service. No GUI or client value is authoritative here. */
final class ShopService {
    private ShopService() {
    }

    static void openShop(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (windowId, inventory, ignored) -> new ShopMenu(RealityEconomyMod.SHOP_MENU, windowId, inventory),
                Component.literal("Reality Shop")));
        if (player.containerMenu instanceof ShopMenu menu) {
            sendSnapshot(player, menu.containerId, 0, "", "shop opened");
        }
    }

    static boolean list(ServerPlayer player) {
        ShopData data = data(player);
        boolean includeInactive = isAdministrator(player) || data.isClerk(player.getUUID());
        List<ShopDomain.CatalogEntry> entries = data.entries(includeInactive);
        player.sendSystemMessage(Component.literal(
                "shop world_epoch=" + data.currentEpoch()
                        + " catalog_revision=" + data.currentRevision()
                        + " entries=" + entries.size()));
        for (ShopDomain.CatalogEntry entry : entries) {
            player.sendSystemMessage(Component.literal(formatEntry(entry)));
        }
        if (data.recoveryBlocked()) {
            player.sendSystemMessage(Component.literal("shop recovery_required=true; new purchases are stopped"));
        }
        return true;
    }

    static boolean detail(ServerPlayer player, String entryId, long expectedRevision) {
        Result result = viewDetail(player, UUID.randomUUID(), expectedRevision, entryId);
        sendCommandResult(player, result);
        return result.success();
    }

    static boolean purchase(ServerPlayer player, String entryId, long expectedRevision) {
        Result result = purchase(
                player,
                UUID.randomUUID(),
                expectedRevision,
                entryId,
                "command",
                null);
        sendCommandResult(player, result);
        return result.success();
    }

    static boolean add(
            ServerPlayer player,
            String itemId,
            int quantity,
            long price,
            long expectedRevision) {
        Result result = add(
                player,
                UUID.randomUUID(),
                expectedRevision,
                itemId,
                quantity,
                price,
                "command");
        sendCommandResult(player, result);
        return result.success();
    }

    static boolean change(
            ServerPlayer player,
            String entryId,
            String itemId,
            int quantity,
            long price,
            long expectedRevision) {
        Result result = change(
                player,
                UUID.randomUUID(),
                expectedRevision,
                entryId,
                itemId,
                quantity,
                price,
                "command");
        sendCommandResult(player, result);
        return result.success();
    }

    static boolean stop(ServerPlayer player, String entryId, long expectedRevision) {
        Result result = stop(player, UUID.randomUUID(), expectedRevision, entryId, "command");
        sendCommandResult(player, result);
        return result.success();
    }

    static boolean resume(ServerPlayer player, String entryId, long expectedRevision) {
        Result result = resume(player, UUID.randomUUID(), expectedRevision, entryId, "command");
        sendCommandResult(player, result);
        return result.success();
    }

    static boolean appoint(ServerPlayer player, String targetName) {
        Result result = appoint(player, UUID.randomUUID(), targetName, "command");
        sendCommandResult(player, result);
        return result.success();
    }

    static boolean revoke(ServerPlayer player, String targetName) {
        Result result = revoke(player, UUID.randomUUID(), targetName, "command");
        sendCommandResult(player, result);
        return result.success();
    }

    static boolean reset(ServerPlayer player) {
        ShopData data = data(player);
        Result result = reset(player, UUID.randomUUID(), data.currentRevision(), true, "command");
        sendCommandResult(player, result);
        return result.success();
    }

    static void handlePacket(ServerPlayer player, ShopNetwork.ShopRequest request) {
        ShopData data = data(player);
        if (!(player.containerMenu instanceof ShopMenu menu) || menu.containerId != request.menuId()) {
            return;
        }

        if (request.requestId() == null || request.actorId() == null
                || !player.getUUID().equals(request.actorId())) {
            sendSnapshot(player, menu.containerId, 0, "", "request actor rejected");
            return;
        }

        ShopDomain.RequestRecord previousRequest = data.requestAny(request.requestId());
        ShopDomain.PurchaseRecord previousPurchase = data.purchaseByRequestAny(request.requestId());
        if (previousRequest != null || previousPurchase != null) {
            UUID previousActor = previousRequest != null ? previousRequest.actor() : previousPurchase.buyer();
            if (!player.getUUID().equals(previousActor)) {
                sendSnapshot(player, menu.containerId, 0, "", "duplicate request identity rejected");
                return;
            }
            String message = previousPurchase != null
                    ? "duplicate purchase request: " + previousPurchase.status().name()
                            + " purchase_id=" + previousPurchase.purchaseId()
                    : "duplicate request: " + previousRequest.message();
            sendSnapshot(player, menu.containerId, request.page(), request.entryId(), message);
            return;
        }

        ShopDomain.Operation operation = ShopDomain.Operation.fromCode(request.operationCode());
        if (request.protocolVersion() != ShopDomain.PROTOCOL_VERSION
                || operation == null
                || !ShopDomain.SHOP_ID.equals(request.shopId())
                || request.worldEpoch() == null
                || !request.worldEpoch().equals(data.currentEpoch())
                || request.revision() < 1L
                || request.revision() != data.currentRevision()
                || request.page() < 0
                || request.page() >= ShopDomain.RETAINED_ENTRY_LIMIT / ShopDomain.MAX_PAGE_SIZE) {
            sendSnapshot(player, menu.containerId, 0, "", "request contract rejected; refresh the shop");
            return;
        }
        if (request.quantity() < 0
                || request.quantity() > ShopDomain.MAX_QUANTITY
                || request.price() < 0L
                || request.price() > ShopDomain.MAX_PRICE) {
            sendSnapshot(player, menu.containerId, request.page(), "", "request input bounds rejected");
            return;
        }

        Result result;
        switch (operation) {
            case LIST -> result = listSnapshot(player, request);
            case DETAIL -> result = viewDetail(player, request.requestId(), request.revision(), request.entryId());
            case PURCHASE -> result = purchase(
                    player,
                    request.requestId(),
                    request.revision(),
                    request.entryId(),
                    "gui",
                    request);
            case ADD -> result = add(
                    player,
                    request.requestId(),
                    request.revision(),
                    request.itemId(),
                    request.quantity(),
                    request.price(),
                    "gui");
            case CHANGE -> result = change(
                    player,
                    request.requestId(),
                    request.revision(),
                    request.entryId(),
                    request.itemId(),
                    request.quantity(),
                    request.price(),
                    "gui");
            case STOP -> result = stop(
                    player,
                    request.requestId(),
                    request.revision(),
                    request.entryId(),
                    "gui");
            case RESUME -> result = resume(
                    player,
                    request.requestId(),
                    request.revision(),
                    request.entryId(),
                    "gui");
            case APPOINT -> result = appoint(player, request.requestId(), request.targetName(), "gui");
            case REVOKE -> result = revoke(player, request.requestId(), request.targetName(), "gui");
            case RESET -> result = reset(
                    player,
                    request.requestId(),
                    request.revision(),
                    request.confirmation(),
                    "gui");
            default -> result = Result.failure("unsupported Shop operation");
        }

        String selected = result.selectedEntryId() == null ? request.entryId() : result.selectedEntryId();
        sendSnapshot(player, menu.containerId, request.page(), selected, result.message());
    }

    private static Result listSnapshot(ServerPlayer player, ShopNetwork.ShopRequest request) {
        ShopData data = data(player);
        if (!revisionMatches(data, request.revision())) {
            return Result.failure("catalog revision is stale; refresh the shop");
        }
        return Result.success("shop list refreshed");
    }

    private static Result viewDetail(
            ServerPlayer player,
            UUID requestId,
            long expectedRevision,
            String entryId) {
        ShopData data = data(player);
        if (!revisionMatches(data, expectedRevision)) {
            return Result.failure("catalog revision is stale; refresh the shop");
        }
        if (!ShopDomain.validId(entryId, ShopDomain.MAX_ID_LENGTH)) {
            return Result.failure("entry id rejected");
        }
        ShopDomain.CatalogEntry entry = data.entry(entryId);
        if (entry == null || (!entry.active() && !canManageCatalog(player, data))) {
            return Result.failure("entry is not visible");
        }
        return Result.success("detail loaded", entry.id());
    }

    private static Result add(
            ServerPlayer player,
            UUID requestId,
            long expectedRevision,
            String itemId,
            int quantity,
            long price,
            String source) {
        ShopData data = data(player);
        if (!canMutateCatalog(player, data, expectedRevision)) {
            return rejectMutation(player, data, requestId, ShopDomain.Operation.ADD, expectedRevision,
                    "clerk permission or catalog revision rejected", source);
        }
        Item item = ItemPolicy.resolve(itemId);
        if (!ItemPolicy.validQuantity(item, quantity) || !ItemPolicy.validPrice(price)) {
            return rejectMutation(player, data, requestId, ShopDomain.Operation.ADD, expectedRevision,
                    "item allowlist, quantity, or price rejected", source);
        }

        String entryId = "entry_" + UUID.randomUUID().toString().replace("-", "");
        ShopData.CatalogMutationResult mutation = data.addEntry(entryId, itemId, quantity, price);
        if (!mutation.applied()) {
            return rejectMutation(player, data, requestId, ShopDomain.Operation.ADD, expectedRevision,
                    mutation.message(), source);
        }
        auditCatalog(player, data, requestId, "ENTRY_ADDED", mutation, expectedRevision, source);
        remember(data, player, requestId, ShopDomain.Operation.ADD, true, mutation.revision(), null,
                "entry added id=" + entryId);
        saveNow(player);
        return Result.success("entry added id=" + entryId + " revision=" + mutation.revision(), entryId);
    }

    private static Result change(
            ServerPlayer player,
            UUID requestId,
            long expectedRevision,
            String entryId,
            String itemId,
            int quantity,
            long price,
            String source) {
        ShopData data = data(player);
        if (!canMutateCatalog(player, data, expectedRevision)) {
            return rejectMutation(player, data, requestId, ShopDomain.Operation.CHANGE, expectedRevision,
                    "clerk permission or catalog revision rejected", source);
        }
        if (!ShopDomain.validId(entryId, ShopDomain.MAX_ID_LENGTH)) {
            return rejectMutation(player, data, requestId, ShopDomain.Operation.CHANGE, expectedRevision,
                    "entry id rejected", source);
        }
        Item item = ItemPolicy.resolve(itemId);
        if (!ItemPolicy.validQuantity(item, quantity) || !ItemPolicy.validPrice(price)) {
            return rejectMutation(player, data, requestId, ShopDomain.Operation.CHANGE, expectedRevision,
                    "item allowlist, quantity, or price rejected", source);
        }

        ShopData.CatalogMutationResult mutation = data.changeEntry(entryId, itemId, quantity, price);
        if (!mutation.applied()) {
            return rejectMutation(player, data, requestId, ShopDomain.Operation.CHANGE, expectedRevision,
                    mutation.message(), source);
        }
        auditCatalog(player, data, requestId, "ENTRY_CHANGED", mutation, expectedRevision, source);
        remember(data, player, requestId, ShopDomain.Operation.CHANGE, true, mutation.revision(), null,
                "entry changed id=" + entryId);
        saveNow(player);
        return Result.success("entry changed id=" + entryId + " revision=" + mutation.revision(), entryId);
    }

    private static Result stop(
            ServerPlayer player,
            UUID requestId,
            long expectedRevision,
            String entryId,
            String source) {
        ShopData data = data(player);
        if (!canMutateCatalog(player, data, expectedRevision)) {
            return rejectMutation(player, data, requestId, ShopDomain.Operation.STOP, expectedRevision,
                    "clerk permission or catalog revision rejected", source);
        }
        if (!ShopDomain.validId(entryId, ShopDomain.MAX_ID_LENGTH)) {
            return rejectMutation(player, data, requestId, ShopDomain.Operation.STOP, expectedRevision,
                    "entry id rejected", source);
        }

        ShopData.CatalogMutationResult mutation = data.stopEntry(entryId);
        if (!mutation.applied()) {
            return rejectMutation(player, data, requestId, ShopDomain.Operation.STOP, expectedRevision,
                    mutation.message(), source);
        }
        auditCatalog(player, data, requestId, "ENTRY_STOPPED", mutation, expectedRevision, source);
        remember(data, player, requestId, ShopDomain.Operation.STOP, true, mutation.revision(), null,
                "entry stopped id=" + entryId);
        saveNow(player);
        return Result.success("entry stopped id=" + entryId + " revision=" + mutation.revision(), entryId);
    }

    private static Result resume(
            ServerPlayer player,
            UUID requestId,
            long expectedRevision,
            String entryId,
            String source) {
        ShopData data = data(player);
        if (!canMutateCatalog(player, data, expectedRevision)) {
            return rejectMutation(player, data, requestId, ShopDomain.Operation.RESUME, expectedRevision,
                    "clerk permission or catalog revision rejected", source);
        }
        if (!ShopDomain.validId(entryId, ShopDomain.MAX_ID_LENGTH)) {
            return rejectMutation(player, data, requestId, ShopDomain.Operation.RESUME, expectedRevision,
                    "entry id rejected", source);
        }
        ShopDomain.CatalogEntry existing = data.entry(entryId);
        if (existing == null || ItemPolicy.resolve(existing.itemId()) == null
                || !ItemPolicy.validQuantity(ItemPolicy.resolve(existing.itemId()), existing.quantity())
                || !ItemPolicy.validPrice(existing.price())) {
            return rejectMutation(player, data, requestId, ShopDomain.Operation.RESUME, expectedRevision,
                    "stored item is no longer safe", source);
        }

        ShopData.CatalogMutationResult mutation = data.resumeEntry(entryId);
        if (!mutation.applied()) {
            return rejectMutation(player, data, requestId, ShopDomain.Operation.RESUME, expectedRevision,
                    mutation.message(), source);
        }
        auditCatalog(player, data, requestId, "ENTRY_RESUMED", mutation, expectedRevision, source);
        remember(data, player, requestId, ShopDomain.Operation.RESUME, true, mutation.revision(), null,
                "entry resumed id=" + entryId);
        saveNow(player);
        return Result.success("entry resumed id=" + entryId + " revision=" + mutation.revision(), entryId);
    }

    private static Result purchase(
            ServerPlayer player,
            UUID requestId,
            long expectedRevision,
            String entryId,
            String source,
            ShopNetwork.ShopRequest request) {
        ShopData data = data(player);
        ShopDomain.PurchaseRecord existing = data.purchaseByRequestAny(requestId);
        if (existing != null) {
            boolean success = existing.status() == ShopDomain.PurchaseStatus.COMMITTED;
            return new Result(
                    success,
                    "purchase already " + existing.status().name() + " purchase_id=" + existing.purchaseId(),
                    existing.entryId());
        }
        if (!revisionMatches(data, expectedRevision)) {
            return purchaseRejected(player, data, requestId, expectedRevision, entryId,
                    "catalog revision is stale; refresh the shop", source);
        }
        if (data.recoveryBlocked()) {
            return purchaseRejected(player, data, requestId, expectedRevision, entryId,
                    "shop recovery is required; purchase is stopped", source);
        }
        if (!ShopDomain.validId(entryId, ShopDomain.MAX_ID_LENGTH)) {
            return purchaseRejected(player, data, requestId, expectedRevision, entryId,
                    "entry id rejected", source);
        }
        ShopDomain.CatalogEntry entry = data.entry(entryId);
        if (entry == null || !entry.active()) {
            return purchaseRejected(player, data, requestId, expectedRevision, entryId,
                    "entry is not active", source);
        }
        Item item = ItemPolicy.resolve(entry.itemId());
        if (!ItemPolicy.validQuantity(item, entry.quantity()) || !ItemPolicy.validPrice(entry.price())) {
            return purchaseRejected(player, data, requestId, expectedRevision, entryId,
                    "stored catalog item is unsafe", source);
        }

        EconomyLedger ledger = EconomyLedger.forLevel(player.serverLevel());
        if (ledger.balanceOf(player.getUUID()) < entry.price()) {
            return purchaseRejected(player, data, requestId, expectedRevision, entryId,
                    "insufficient balance", source);
        }
        if (!canFit(player.getInventory(), item, entry.quantity())) {
            return purchaseRejected(player, data, requestId, expectedRevision, entryId,
                    "inventory capacity is insufficient", source);
        }

        UUID purchaseId = UUID.randomUUID();
        ShopDomain.PurchaseRecord pending = new ShopDomain.PurchaseRecord(
                purchaseId,
                requestId,
                player.getUUID(),
                data.currentEpoch(),
                ShopDomain.SHOP_ID,
                entry.id(),
                entry.itemId(),
                entry.quantity(),
                entry.price(),
                expectedRevision,
                ShopDomain.PurchaseStatus.PENDING,
                Instant.now().toEpochMilli(),
                "journaled before item delivery");
        data.addPurchase(pending);
        saveNow(player);

        ItemStack delivery = new ItemStack(item, entry.quantity());
        boolean delivered = player.getInventory().add(delivery) && delivery.isEmpty();
        if (!delivered) {
            data.updatePurchase(purchaseId, ShopDomain.PurchaseStatus.RECOVERY_REQUIRED,
                    "inventory delivery could not be confirmed");
            auditPurchase(player, data, requestId, purchaseId, entry, "PURCHASE_RECOVERY_REQUIRED",
                    "item delivery could not be confirmed", source);
            remember(data, player, requestId, ShopDomain.Operation.PURCHASE, false,
                    data.currentRevision(), purchaseId, "purchase recovery required");
            saveNow(player);
            return new Result(false, "purchase recovery required; no debit was applied", entry.id());
        }

        data.updatePurchase(purchaseId, ShopDomain.PurchaseStatus.ITEM_DELIVERED,
                "item delivery confirmed; debit pending");
        saveNow(player);

        EconomyLedger.DebitResult debit = ledger.debitForPurchase(
                player.getUUID(), entry.price(), purchaseId);
        if (!debit.applied()) {
            data.updatePurchase(purchaseId, ShopDomain.PurchaseStatus.RECOVERY_REQUIRED,
                    debit.conflict() ? "debit transaction conflict" : "debit could not be applied");
            auditPurchase(player, data, requestId, purchaseId, entry, "PURCHASE_RECOVERY_REQUIRED",
                    "item delivered but debit did not commit", source);
            remember(data, player, requestId, ShopDomain.Operation.PURCHASE, false,
                    data.currentRevision(), purchaseId, "purchase recovery required");
            saveNow(player);
            return new Result(false, "purchase recovery required; item delivery is retained", entry.id());
        }

        data.updatePurchase(purchaseId, ShopDomain.PurchaseStatus.COMMITTED,
                "item delivery and debit committed");
        auditPurchase(player, data, requestId, purchaseId, entry, "PURCHASE_COMMITTED",
                "purchase committed", source);
        remember(data, player, requestId, ShopDomain.Operation.PURCHASE, true,
                data.currentRevision(), purchaseId, "purchase committed purchase_id=" + purchaseId);
        saveNow(player);
        return new Result(true, "purchase committed purchase_id=" + purchaseId
                + " balance=" + debit.balance(), entry.id());
    }

    private static Result appoint(ServerPlayer player, UUID requestId, String targetName, String source) {
        ShopData data = data(player);
        if (!isAdministrator(player)) {
            return adminRejected(player, data, requestId, ShopDomain.Operation.APPOINT,
                    "permission level 2 is required", source);
        }
        ServerPlayer target = findOnlinePlayer(player, targetName);
        if (target == null) {
            return adminRejected(player, data, requestId, ShopDomain.Operation.APPOINT,
                    "target player must be online", source);
        }
        if (target.getUUID().equals(player.getUUID())) {
            return adminRejected(player, data, requestId, ShopDomain.Operation.APPOINT,
                    "an actor cannot appoint self", source);
        }
        ShopData.SimpleResult result = data.appoint(target.getUUID());
        auditSimple(player, data, requestId, target, "CLERK_APPOINTED", result, source);
        remember(data, player, requestId, ShopDomain.Operation.APPOINT, result.applied(),
                data.currentRevision(), null, result.message());
        saveNow(player);
        return new Result(result.applied(), result.message() + " player=" + target.getGameProfile().getName(), null);
    }

    private static Result revoke(ServerPlayer player, UUID requestId, String targetName, String source) {
        ShopData data = data(player);
        if (!isAdministrator(player)) {
            return adminRejected(player, data, requestId, ShopDomain.Operation.REVOKE,
                    "permission level 2 is required", source);
        }
        ServerPlayer target = findOnlinePlayer(player, targetName);
        if (target == null) {
            return adminRejected(player, data, requestId, ShopDomain.Operation.REVOKE,
                    "target player must be online", source);
        }
        if (target.getUUID().equals(player.getUUID())) {
            return adminRejected(player, data, requestId, ShopDomain.Operation.REVOKE,
                    "an actor cannot revoke self", source);
        }
        ShopData.SimpleResult result = data.revoke(target.getUUID());
        auditSimple(player, data, requestId, target, "CLERK_REVOKED", result, source);
        remember(data, player, requestId, ShopDomain.Operation.REVOKE, result.applied(),
                data.currentRevision(), null, result.message());
        saveNow(player);
        return new Result(result.applied(), result.message() + " player=" + target.getGameProfile().getName(), null);
    }

    private static Result reset(
            ServerPlayer player,
            UUID requestId,
            long expectedRevision,
            boolean confirmation,
            String source) {
        ShopData data = data(player);
        if (!isAdministrator(player)) {
            return adminRejected(player, data, requestId, ShopDomain.Operation.RESET,
                    "permission level 2 is required", source);
        }
        if (!revisionMatches(data, expectedRevision)) {
            return adminRejected(player, data, requestId, ShopDomain.Operation.RESET,
                    "catalog revision is stale; refresh the shop", source);
        }
        if (!confirmation) {
            return adminRejected(player, data, requestId, ShopDomain.Operation.RESET,
                    "reset confirmation is required", source);
        }
        ShopData.ResetResult result = data.reset(player.getUUID(), requestId, Instant.now().toEpochMilli());
        EconomyLedger.forLevel(player.serverLevel()).resetToEpoch(result.newEpoch());
        remember(data, player, requestId, ShopDomain.Operation.RESET, true, result.revision(), null,
                "world reset new_epoch=" + result.newEpoch());
        saveNow(player);
        return Result.success("world reset applied new_epoch=" + result.newEpoch(), null);
    }

    private static Result purchaseRejected(
            ServerPlayer player,
            ShopData data,
            UUID requestId,
            long expectedRevision,
            String entryId,
            String message,
            String source) {
        ShopDomain.CatalogEntry entry = data.entry(entryId);
        if (entry != null) {
            auditPurchase(player, data, requestId, null, entry, "PURCHASE_REJECTED", message, source);
        }
        remember(data, player, requestId, ShopDomain.Operation.PURCHASE, false,
                data.currentRevision(), null, message);
        saveNow(player);
        return Result.failure(message, entryId);
    }

    private static Result rejectMutation(
            ServerPlayer player,
            ShopData data,
            UUID requestId,
            ShopDomain.Operation operation,
            long expectedRevision,
            String message,
            String source) {
        auditRejected(player, data, requestId, operation, expectedRevision, message, source);
        remember(data, player, requestId, operation, false, data.currentRevision(), null, message);
        saveNow(player);
        return Result.failure(message);
    }

    private static Result adminRejected(
            ServerPlayer player,
            ShopData data,
            UUID requestId,
            ShopDomain.Operation operation,
            String message,
            String source) {
        auditRejected(player, data, requestId, operation, data.currentRevision(), message, source);
        remember(data, player, requestId, operation, false, data.currentRevision(), null, message);
        saveNow(player);
        return Result.failure(message);
    }

    private static void auditCatalog(
            ServerPlayer player,
            ShopData data,
            UUID requestId,
            String action,
            ShopData.CatalogMutationResult mutation,
            long expectedRevision,
            String source) {
        ShopDomain.CatalogEntry before = mutation.before();
        ShopDomain.CatalogEntry after = mutation.after();
        data.appendAudit(new ShopDomain.AuditRecord(
                UUID.randomUUID(),
                Instant.now().toEpochMilli(),
                data.currentEpoch(),
                ShopDomain.SHOP_ID,
                player.getUUID(),
                null,
                action,
                after.id(),
                before == null ? "" : before.itemId(),
                before == null ? 0 : before.quantity(),
                before == null ? 0L : before.price(),
                before != null && before.active(),
                after.itemId(),
                after.quantity(),
                after.price(),
                after.active(),
                mutation.revision(),
                expectedRevision,
                requestId,
                null,
                "applied",
                source));
    }

    private static void auditPurchase(
            ServerPlayer player,
            ShopData data,
            UUID requestId,
            UUID purchaseId,
            ShopDomain.CatalogEntry entry,
            String action,
            String message,
            String source) {
        data.appendAudit(new ShopDomain.AuditRecord(
                UUID.randomUUID(),
                Instant.now().toEpochMilli(),
                data.currentEpoch(),
                ShopDomain.SHOP_ID,
                player.getUUID(),
                player.getUUID(),
                action,
                entry.id(),
                entry.itemId(),
                entry.quantity(),
                entry.price(),
                entry.active(),
                entry.itemId(),
                entry.quantity(),
                entry.price(),
                entry.active(),
                data.currentRevision(),
                data.currentRevision(),
                requestId,
                purchaseId,
                action,
                source + ":" + message));
    }

    private static void auditSimple(
            ServerPlayer player,
            ShopData data,
            UUID requestId,
            ServerPlayer target,
            String action,
            ShopData.SimpleResult result,
            String source) {
        data.appendAudit(new ShopDomain.AuditRecord(
                UUID.randomUUID(),
                Instant.now().toEpochMilli(),
                data.currentEpoch(),
                ShopDomain.SHOP_ID,
                player.getUUID(),
                target.getUUID(),
                action,
                "",
                "",
                0,
                0L,
                false,
                "",
                0,
                0L,
                false,
                data.currentRevision(),
                data.currentRevision(),
                requestId,
                null,
                result.applied() ? "applied" : "rejected",
                source + ":" + result.message()));
    }

    private static void auditRejected(
            ServerPlayer player,
            ShopData data,
            UUID requestId,
            ShopDomain.Operation operation,
            long expectedRevision,
            String message,
            String source) {
        data.appendAudit(new ShopDomain.AuditRecord(
                UUID.randomUUID(),
                Instant.now().toEpochMilli(),
                data.currentEpoch(),
                ShopDomain.SHOP_ID,
                player.getUUID(),
                null,
                "MUTATION_REJECTED",
                "",
                "",
                0,
                0L,
                false,
                "",
                0,
                0L,
                false,
                data.currentRevision(),
                expectedRevision,
                requestId,
                null,
                operation.name(),
                source + ":" + message));
    }

    private static void remember(
            ShopData data,
            ServerPlayer player,
            UUID requestId,
            ShopDomain.Operation operation,
            boolean applied,
            long revision,
            UUID purchaseId,
            String message) {
        data.recordRequest(new ShopDomain.RequestRecord(
                requestId,
                player.getUUID(),
                data.currentEpoch(),
                ShopDomain.SHOP_ID,
                operation,
                applied,
                revision,
                purchaseId,
                Instant.now().toEpochMilli(),
                message));
    }

    private static boolean canMutateCatalog(ServerPlayer player, ShopData data, long expectedRevision) {
        return data.isClerk(player.getUUID()) && revisionMatches(data, expectedRevision);
    }

    private static boolean canManageCatalog(ServerPlayer player, ShopData data) {
        return isAdministrator(player) || data.isClerk(player.getUUID());
    }

    private static boolean isAdministrator(ServerPlayer player) {
        return player.hasPermissions(2);
    }

    private static boolean revisionMatches(ShopData data, long expectedRevision) {
        return expectedRevision == data.currentRevision();
    }

    private static ServerPlayer findOnlinePlayer(ServerPlayer actor, String name) {
        if (name == null || name.isBlank() || name.length() > ShopDomain.MAX_PLAYER_NAME_LENGTH) {
            return null;
        }
        return actor.server.getPlayerList().getPlayerByName(name);
    }

    private static ShopData data(ServerPlayer player) {
        ShopData data = ShopData.forLevel(player.serverLevel());
        EconomyLedger ledger = EconomyLedger.forLevel(player.serverLevel());
        String ledgerEpoch = ledger.currentEpoch();
        ledger.ensureEpoch(data.currentEpoch());
        if (!ledgerEpoch.equals(ledger.currentEpoch())) {
            saveNow(player);
        }
        if (data.reconcile(ledger::hasPurchaseDebit)) {
            saveNow(player);
        }
        return data;
    }

    private static boolean canFit(Inventory inventory, Item item, int quantity) {
        int remaining = quantity;
        int maxStackSize = item.getMaxStackSize();
        for (ItemStack existing : inventory.items) {
            if (existing.isEmpty()) {
                remaining -= maxStackSize;
            } else if (ItemStack.isSameItemSameTags(existing, new ItemStack(item, 1))) {
                remaining -= Math.max(0, existing.getMaxStackSize() - existing.getCount());
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return false;
    }

    private static void sendSnapshot(
            ServerPlayer player,
            int menuId,
            int requestedPage,
            String selectedEntryId,
            String message) {
        ShopData data = data(player);
        boolean administrator = isAdministrator(player);
        boolean clerk = data.isClerk(player.getUUID());
        boolean includeInactive = administrator || clerk;
        List<ShopDomain.CatalogEntry> allEntries = data.entries(includeInactive);
        int maxPage = Math.max(0, (allEntries.size() - 1) / ShopDomain.MAX_PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        int from = Math.min(page * ShopDomain.MAX_PAGE_SIZE, allEntries.size());
        int to = Math.min(from + ShopDomain.MAX_PAGE_SIZE, allEntries.size());
        List<ShopNetwork.EntryView> entries = new ArrayList<>();
        for (int index = from; index < to; index++) {
            entries.add(toView(allEntries.get(index)));
        }
        ShopNetwork.EntryView selected = null;
        if (selectedEntryId != null && ShopDomain.validId(selectedEntryId, ShopDomain.MAX_ID_LENGTH)) {
            ShopDomain.CatalogEntry entry = data.entry(selectedEntryId);
            if (entry != null && (entry.active() || includeInactive)) {
                selected = toView(entry);
            }
        }
        ShopNetwork.sendTo(player, new ShopNetwork.ShopSnapshot(
                ShopDomain.PROTOCOL_VERSION,
                menuId,
                data.currentEpoch(),
                ShopDomain.SHOP_ID,
                data.currentRevision(),
                clerk,
                administrator,
                data.recoveryBlocked(),
                page,
                allEntries.size(),
                selected == null ? "" : selected.id(),
                selected,
                entries,
                message == null ? "" : message));
    }

    private static ShopNetwork.EntryView toView(ShopDomain.CatalogEntry entry) {
        return new ShopNetwork.EntryView(
                entry.id(), entry.itemId(), entry.quantity(), entry.price(), entry.active());
    }

    private static void sendCommandResult(ServerPlayer player, Result result) {
        player.sendSystemMessage(Component.literal("shop " + result.message()));
    }

    private static void saveNow(ServerPlayer player) {
        player.server.overworld().getDataStorage().save();
    }

    private static String formatEntry(ShopDomain.CatalogEntry entry) {
        return "entry=" + entry.id()
                + " item=" + entry.itemId()
                + " quantity=" + entry.quantity()
                + " price=" + entry.price()
                + " active=" + entry.active();
    }

    private record Result(boolean success, String message, String selectedEntryId) {
        static Result success(String message) {
            return new Result(true, message, null);
        }

        static Result success(String message, String selectedEntryId) {
            return new Result(true, message, selectedEntryId);
        }

        static Result failure(String message) {
            return new Result(false, message, null);
        }

        static Result failure(String message, String selectedEntryId) {
            return new Result(false, message, selectedEntryId);
        }
    }
}
