package com.vvvvdddd.bind.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vvvvdddd.bind.core.Actor;
import com.vvvvdddd.bind.core.BindingException;
import com.vvvvdddd.bind.core.BindingPage;
import com.vvvvdddd.bind.core.BindingRecord;
import com.vvvvdddd.bind.core.BindingType;
import com.vvvvdddd.bind.core.BuiltinTypes;
import com.vvvvdddd.bind.core.ErrorCode;
import com.vvvvdddd.bind.core.FieldDefinition;
import com.vvvvdddd.bind.core.Mask;
import com.vvvvdddd.bind.core.PlayerIdentity;
import com.vvvvdddd.bind.core.TypeRegistry;
import com.vvvvdddd.bind.core.ValidatedBinding;
import com.vvvvdddd.bind.core.ValidatorRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteBindingRepositoryTest {
    private static final PlayerIdentity OWNER = new PlayerIdentity(UUID.randomUUID(), "Owner");
    private static final PlayerIdentity OTHER = new PlayerIdentity(UUID.randomUUID(), "Other");
    @TempDir
    Path directory;

    @Test
    void constructionDoesNotTouchDiskAndInitializeRunsSchemaInBackground() throws Exception {
        Path file = directory.resolve("nested/data.db");
        try (SqliteBindingRepository repository = repository(file)) {
            assertFalse(Files.exists(file.getParent()));
            failure(repository.list(OWNER.uuid(), null, 1, 10), ErrorCode.UNAVAILABLE);
            await(repository.initialize(types(type("qq", -1))));
            assertTrue(Files.isRegularFile(file));
            try (Connection connection = connect(file); Statement statement = connection.createStatement()) {
                try (ResultSet rows = statement.executeQuery("PRAGMA user_version")) {
                    assertEquals(1, rows.getInt(1));
                }
                try (ResultSet rows = statement.executeQuery("PRAGMA journal_mode")) {
                    assertEquals("wal", rows.getString(1));
                }
            }
        }
        Files.delete(file);
    }

    @Test
    void keepsMultipleValuesAndTypesAndAllowsSameValueAcrossPlayers() throws Exception {
        BindingType qq = type("qq", -1);
        BindingType email = type("email", -1);
        try (SqliteBindingRepository repository = open(types(qq, email))) {
            BindingRecord first = await(repository.add(OWNER, Actor.player(OWNER.uuid()), binding(qq, "111111")));
            await(repository.add(OWNER, Actor.console(), binding(qq, "222222")));
            await(repository.add(OWNER, Actor.console(), binding(email, "test@example.invalid")));
            await(repository.add(OTHER, Actor.console(), binding(qq, "111111")));
            BindingPage all = await(repository.list(OWNER.uuid(), null, 1, 2));
            assertEquals(3, all.total());
            assertEquals(2, all.records().size());
            assertEquals(first.id(), all.records().getFirst().id());
            assertEquals(1, await(repository.list(OWNER.uuid(), null, 2, 2)).records().size());
            assertEquals(2, await(repository.list(OWNER.uuid(), "qq", 1, 10)).total());
            assertEquals(1, await(repository.list(OTHER.uuid(), null, 1, 10)).total());
            assertEquals(Actor.player(OWNER.uuid()), await(repository.view(OWNER.uuid(), first.id())).actor());
        }
    }

    @Test
    void rejectsOwnDuplicateBeforeLimit() throws Exception {
        BindingType qq = type("qq", 1);
        try (SqliteBindingRepository repository = open(types(qq))) {
            await(repository.add(OWNER, Actor.console(), binding(qq, "111111")));
            failure(repository.add(OWNER, Actor.console(), binding(qq, "111111")), ErrorCode.DUPLICATE);
            failure(repository.add(OWNER, Actor.console(), binding(qq, "222222")), ErrorCode.LIMIT_REACHED);
            assertEquals(1, await(repository.list(OWNER.uuid(), null, 1, 10)).total());
        }
    }

    @Test
    void persistsMultiplePhonesDeduplicatesPerOwnerAndAllowsCrossPlayerDuplicates() throws Exception {
        TypeRegistry builtins = new TypeRegistry(BuiltinTypes.defaults());
        BindingType phone = builtins.findId("phone").orElseThrow();
        ValidatorRegistry validators = new ValidatorRegistry();
        Path file = directory.resolve("phones.db");
        BindingRecord first;
        try (SqliteBindingRepository repository = repository(file)) {
            await(repository.initialize(builtins));
            first = await(repository.add(OWNER, Actor.player(OWNER.uuid()),
                    validators.normalizeAndValidate(phone, List.of("13800000000"))));
            BindingRecord second = await(repository.add(OWNER, Actor.console(),
                    validators.normalizeAndValidate(phone, List.of("13900000000"))));
            failure(repository.add(OWNER, Actor.console(),
                    validators.normalizeAndValidate(phone, List.of(" 13800000000 "))), ErrorCode.DUPLICATE);
            BindingRecord other = await(repository.add(OTHER, Actor.console(),
                    validators.normalizeAndValidate(phone, List.of("13800000000"))));
            assertEquals(2, await(repository.list(OWNER.uuid(), "phone", 1, 10)).total());
            assertEquals(1, await(repository.list(OTHER.uuid(), "phone", 1, 10)).total());
            assertTrue(second.id() > first.id());
            assertEquals(first.values(), other.values());
            failure(repository.view(OTHER.uuid(), first.id()), ErrorCode.NOT_FOUND);
        }
        try (SqliteBindingRepository repository = repository(file)) {
            await(repository.initialize(builtins));
            assertEquals(first, await(repository.view(OWNER.uuid(), first.id())));
            assertEquals("13800000000", await(repository.view(OWNER.uuid(), first.id())).values().get("value"));
        }
    }

    @Test
    void appliesTheDefaultPhoneLimitAndKeepsDuplicateErrorsDeterministic() throws Exception {
        TypeRegistry builtins = new TypeRegistry(BuiltinTypes.defaults());
        BindingType phone = builtins.findId("phone").orElseThrow();
        ValidatorRegistry validators = new ValidatorRegistry();
        try (SqliteBindingRepository repository = open(builtins)) {
            for (int index = 0; index < 10; index++) {
                await(repository.add(OWNER, Actor.console(), validators.normalizeAndValidate(phone,
                        List.of("1380000000" + index))));
            }
            failure(repository.add(OWNER, Actor.console(), validators.normalizeAndValidate(phone,
                    List.of("13800000000"))), ErrorCode.DUPLICATE);
            failure(repository.add(OWNER, Actor.console(), validators.normalizeAndValidate(phone,
                    List.of("13900000000"))), ErrorCode.LIMIT_REACHED);
            await(repository.add(OTHER, Actor.console(), validators.normalizeAndValidate(phone,
                    List.of("13800000000"))));
            assertEquals(10, await(repository.list(OWNER.uuid(), "phone", 1, 10)).total());
            assertEquals(1, await(repository.list(OTHER.uuid(), "phone", 1, 10)).total());
        }
    }

    @Test
    void addsPhoneToTheExistingThreeTypesWithoutChangingSavedRecords() throws Exception {
        List<BindingType> defaults = BuiltinTypes.defaults();
        TypeRegistry previous = new TypeRegistry(defaults.subList(0, 3));
        ValidatorRegistry validators = new ValidatorRegistry();
        Path file = directory.resolve("upgrade.db");
        BindingRecord legacy;
        try (SqliteBindingRepository repository = repository(file)) {
            await(repository.initialize(previous));
            legacy = await(repository.add(OWNER, Actor.console(), validators.normalizeAndValidate(
                    previous.findId("qq").orElseThrow(), List.of("123456"))));
        }
        try (SqliteBindingRepository repository = repository(file)) {
            TypeRegistry current = new TypeRegistry(defaults);
            await(repository.initialize(current));
            assertEquals(legacy, await(repository.view(OWNER.uuid(), legacy.id())));
            BindingRecord phone = await(repository.add(OWNER, Actor.console(), validators.normalizeAndValidate(
                    current.findId("phone").orElseThrow(), List.of("13800000000"))));
            assertTrue(phone.id() > legacy.id());
            assertEquals(2, await(repository.list(OWNER.uuid(), null, 1, 10)).total());
        }
    }

    @Test
    void enforcesLimitsAtomicallyAcrossIndependentConnections() throws Exception {
        Path file = directory.resolve("data.db");
        BindingType qq = type("qq", 2);
        try (SqliteBindingRepository first = repository(file); SqliteBindingRepository second = repository(file)) {
            await(first.initialize(types(qq)));
            await(second.initialize(types(qq)));
            List<CompletableFuture<BindingRecord>> writes = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                writes.add((i % 2 == 0 ? first : second).add(OWNER, Actor.console(), binding(qq, "value" + i)));
            }
            int successes = 0;
            for (CompletableFuture<BindingRecord> write : writes) {
                try {
                    await(write);
                    successes++;
                } catch (CompletionException failure) {
                    assertEquals(ErrorCode.LIMIT_REACHED,
                            assertInstanceOf(BindingException.class, failure.getCause()).code());
                }
            }
            assertEquals(2, successes);
            assertEquals(2, await(first.list(OWNER.uuid(), null, 1, 10)).total());
        }
    }

    @Test
    void persistsRecordsAndNeverReusesDeletedIds() throws Exception {
        Path file = directory.resolve("data.db");
        BindingType qq = type("qq", -1);
        BindingRecord first;
        BindingRecord deleted;
        try (SqliteBindingRepository repository = repository(file)) {
            await(repository.initialize(types(qq)));
            first = await(repository.add(OWNER, Actor.console(), binding(qq, "111111")));
            deleted = await(repository.add(OWNER, Actor.console(), binding(qq, "222222")));
            assertTrue(await(repository.remove(OWNER.uuid(), deleted.id())));
        }
        try (SqliteBindingRepository repository = repository(file)) {
            await(repository.initialize(types(qq)));
            assertEquals(first, await(repository.view(OWNER.uuid(), first.id())));
            assertEquals(OWNER, await(repository.resolvePlayer(OWNER.uuid().toString())));
            BindingRecord next = await(repository.add(OWNER, Actor.console(), binding(qq, "333333")));
            assertTrue(next.id() > deleted.id());
        }
    }

    @Test
    void scopesViewAndDeleteToOwner() throws Exception {
        BindingType qq = type("qq", -1);
        try (SqliteBindingRepository repository = open(types(qq))) {
            BindingRecord record = await(repository.add(OWNER, Actor.console(), binding(qq, "111111")));
            failure(repository.view(OTHER.uuid(), record.id()), ErrorCode.NOT_FOUND);
            assertFalse(await(repository.remove(OTHER.uuid(), record.id())));
            assertNotNull(await(repository.view(OWNER.uuid(), record.id())));
            assertTrue(await(repository.remove(OWNER.uuid(), record.id())));
            assertFalse(await(repository.remove(OWNER.uuid(), record.id())));
            failure(repository.view(OWNER.uuid(), record.id()), ErrorCode.NOT_FOUND);
        }
    }

    @Test
    void directoryHandlesRenameCaseAndAmbiguousNames() throws Exception {
        try (SqliteBindingRepository repository = open(types(type("qq", -1)))) {
            await(repository.rememberPlayer(OWNER));
            assertEquals(OWNER, await(repository.resolvePlayer("oWnEr")));
            PlayerIdentity renamed = new PlayerIdentity(OWNER.uuid(), "Renamed");
            await(repository.rememberPlayer(renamed));
            failure(repository.resolvePlayer("Owner"), ErrorCode.NOT_FOUND);
            assertEquals(renamed, await(repository.resolvePlayer("RENAMED")));
            await(repository.rememberPlayer(new PlayerIdentity(OTHER.uuid(), "renamed")));
            failure(repository.resolvePlayer("renamed"), ErrorCode.AMBIGUOUS_PLAYER);
            assertEquals(renamed, await(repository.resolvePlayer(OWNER.uuid().toString())));
            failure(repository.resolvePlayer(UUID.randomUUID().toString()), ErrorCode.NOT_FOUND);
            PlayerIdentity proxyName = new PlayerIdentity(UUID.randomUUID(), "代理名称".repeat(12));
            await(repository.rememberPlayer(proxyName));
            assertEquals(proxyName, await(repository.resolvePlayer(proxyName.name())));
        }
    }

    @Test
    void protectsStructuresWithDataAndKeepsOldRegistryAfterFailure() throws Exception {
        BindingType qq = type("qq", 2);
        FieldDefinition required = field("value", "text", true, 100, Mask.PARTIAL);
        List<BindingType> incompatible = List.of(
                new BindingType("qq", 2, "QQ", true, 2, qq.fields(), qq.dedupeFields()),
                new BindingType("qq", 1, "QQ", true, 2,
                        List.of(field("renamed", "text", true, 100, Mask.PARTIAL)), List.of("renamed")),
                new BindingType("qq", 1, "QQ", true, 2,
                        List.of(field("value", "qq", true, 100, Mask.PARTIAL)), List.of("value")),
                new BindingType("qq", 1, "QQ", true, 2,
                        List.of(field("value", "text", false, 100, Mask.PARTIAL)), List.of("value")),
                new BindingType("qq", 1, "QQ", true, 2,
                        List.of(field("value", "text", true, 99, Mask.PARTIAL)), List.of("value")),
                new BindingType("qq", 1, "QQ", true, 2,
                        List.of(required, field("note", "text", false, 100, Mask.HIDDEN)), List.of("value")));
        try (SqliteBindingRepository repository = open(types(qq))) {
            await(repository.add(OWNER, Actor.console(), binding(qq, "111111")));
            for (BindingType changed : incompatible) {
                failure(repository.synchronizeTypes(types(changed)), ErrorCode.INCOMPATIBLE_SCHEMA);
            }
            failure(repository.synchronizeTypes(types(type("email", -1))), ErrorCode.INCOMPATIBLE_SCHEMA);
            await(repository.add(OWNER, Actor.console(), binding(qq, "222222")));
            assertEquals(2, await(repository.list(OWNER.uuid(), null, 1, 10)).total());
        }
    }

    @Test
    void protectsFieldOrderAndDedupeFields() throws Exception {
        FieldDefinition one = field("one", "text", true, 100, Mask.PLAIN);
        FieldDefinition two = field("two", "text", true, 100, Mask.PLAIN);
        BindingType initial = new BindingType("custom", 1, "自定义", true, -1,
                List.of(one, two), List.of("one", "two"));
        try (SqliteBindingRepository repository = open(types(initial))) {
            await(repository.add(OWNER, Actor.console(),
                    new ValidatedBinding(initial, Map.of("one", "1", "two", "2"), "key")));
            BindingType reordered = new BindingType("custom", 1, "自定义", true, -1,
                    List.of(two, one), List.of("one", "two"));
            failure(repository.synchronizeTypes(types(reordered)), ErrorCode.INCOMPATIBLE_SCHEMA);
            BindingType changedDedupe = new BindingType("custom", 1, "自定义", true, -1,
                    List.of(one, two), List.of("one"));
            failure(repository.synchronizeTypes(types(changedDedupe)), ErrorCode.INCOMPATIBLE_SCHEMA);
        }
    }

    @Test
    void permitsDisplayMaskEnabledAndLimitUpdatesWithoutChangingData() throws Exception {
        BindingType original = type("qq", -1);
        BindingType changed = new BindingType("qq", 1, "联系QQ", false, 1,
                List.of(new FieldDefinition("value", "新字段名", "text", true, 100, Mask.HIDDEN)), List.of("value"));
        try (SqliteBindingRepository repository = open(types(original))) {
            BindingRecord first = await(repository.add(OWNER, Actor.console(), binding(original, "111111")));
            await(repository.synchronizeTypes(types(changed)));
            assertEquals(first, await(repository.view(OWNER.uuid(), first.id())));
            failure(repository.add(OWNER, Actor.console(), binding(original, "222222")), ErrorCode.DISABLED_TYPE);
            BindingType enabled = new BindingType(changed.id(), changed.version(), changed.name(), true,
                    changed.limit(), changed.fields(), changed.dedupeFields());
            await(repository.synchronizeTypes(types(enabled)));
            failure(repository.add(OWNER, Actor.console(), binding(original, "222222")), ErrorCode.LIMIT_REACHED);
        }
    }

    @Test
    void permitsChangingAndRemovingTypesWithoutData() throws Exception {
        BindingType original = type("qq", -1);
        try (SqliteBindingRepository repository = open(types(original))) {
            BindingType changed = new BindingType("qq", 2, "QQ", true, 2,
                    List.of(field("number", "qq", true, 20, Mask.HIDDEN)), List.of("number"));
            await(repository.synchronizeTypes(types(changed)));
            await(repository.synchronizeTypes(types(type("email", -1))));
            failure(repository.list(OWNER.uuid(), "qq", 1, 10), ErrorCode.UNKNOWN_TYPE);
        }
    }

    @Test
    void rejectsIncompatibleRegistryOnRestartAndAllowsOriginalRegistry() throws Exception {
        Path file = directory.resolve("data.db");
        BindingType original = type("qq", -1);
        try (SqliteBindingRepository repository = repository(file)) {
            await(repository.initialize(types(original)));
            await(repository.add(OWNER, Actor.console(), binding(original, "111111")));
        }
        try (SqliteBindingRepository repository = repository(file)) {
            failure(repository.initialize(types(type("email", -1))), ErrorCode.INCOMPATIBLE_SCHEMA);
            failure(repository.list(OWNER.uuid(), null, 1, 10), ErrorCode.UNAVAILABLE);
            await(repository.initialize(types(original)));
            assertEquals(1, await(repository.list(OWNER.uuid(), null, 1, 10)).total());
        }
    }

    @Test
    void treatsValuesAsDataAndRejectsUnknownOrOversizedFields() throws Exception {
        BindingType type = type("custom", -1);
        String value = "a'b\"; DROP TABLE bindings;--";
        try (SqliteBindingRepository repository = open(types(type))) {
            BindingRecord record = await(repository.add(OWNER, Actor.console(), binding(type, value)));
            assertEquals(value, await(repository.view(OWNER.uuid(), record.id())).values().get("value"));
            failure(repository.add(OWNER, Actor.console(),
                    new ValidatedBinding(type, Map.of("unknown", "secret"), "unknown")), ErrorCode.INVALID_INPUT);
            failure(repository.add(OWNER, Actor.console(), binding(type, "x".repeat(101))), ErrorCode.INVALID_INPUT);
        }
    }

    @Test
    void rejectsMalformedOrNonFlatStoredJsonWithoutLeakingContent() throws Exception {
        Path file = directory.resolve("data.db");
        BindingType type = type("custom", -1);
        try (SqliteBindingRepository repository = repository(file)) {
            await(repository.initialize(types(type)));
            BindingRecord record = await(repository.add(OWNER, Actor.console(), binding(type, "original")));
            List<String> corrupted = List.of(
                    "{\"value\":{\"secret\":\"nested\"}}", "{\"value\":12345}", "{\"value\":null}",
                    "{\"unknown\":\"secret\"}", "{\"value\":\"first\",\"value\":\"secret\"}",
                    "{\"value\":\"\\ud800\"}", "{\"value\":\"secret\\nforged\"}",
                    "{}", "[\"secret\"]", "{\"value\":\"secret\"} trailing", "{invalid secret}",
                    "{\"value\":\"" + "s".repeat(9000) + "\"}");
            try (Connection external = connect(file); Statement settings = external.createStatement()) {
                settings.execute("PRAGMA ignore_check_constraints = ON");
                for (String json : corrupted) {
                    try (PreparedStatement statement = external.prepareStatement(
                            "UPDATE bindings SET values_json = ? WHERE id = ?")) {
                        statement.setString(1, json);
                        statement.setLong(2, record.id());
                        statement.executeUpdate();
                    }
                    BindingException failure = failure(repository.view(OWNER.uuid(), record.id()), ErrorCode.STORAGE_ERROR);
                    assertEquals("STORAGE_ERROR", failure.getMessage());
                    assertNull(failure.getCause());
                }
            }
        }
    }

    @Test
    void reportsBusyAndRecoversAfterExternalWriteLock() throws Exception {
        Path file = directory.resolve("data.db");
        BindingType type = type("qq", -1);
        try (SqliteBindingRepository repository = new SqliteBindingRepository(file, 256, 30)) {
            await(repository.initialize(types(type)));
            try (Connection external = connect(file); Statement statement = external.createStatement()) {
                statement.execute("BEGIN IMMEDIATE");
                failure(repository.add(OWNER, Actor.console(), binding(type, "secret")), ErrorCode.BUSY);
                statement.execute("ROLLBACK");
            }
            await(repository.add(OWNER, Actor.console(), binding(type, "111111")));
            assertEquals(1, await(repository.list(OWNER.uuid(), null, 1, 10)).total());
        }
    }

    @Test
    void boundsQueuedWorkAndCompletesRejectedFutures() throws Exception {
        Path file = directory.resolve("data.db");
        BindingType type = type("qq", -1);
        try (SqliteBindingRepository repository = new SqliteBindingRepository(file, 1, 5000)) {
            await(repository.initialize(types(type)));
            List<CompletableFuture<BindingRecord>> writes = new ArrayList<>();
            try (Connection external = connect(file); Statement statement = external.createStatement()) {
                statement.execute("BEGIN IMMEDIATE");
                for (int i = 0; i < 40; i++) {
                    writes.add(repository.add(OWNER, Actor.console(), binding(type, "value" + i)));
                }
                long rejected = writes.stream().filter(CompletableFuture::isCompletedExceptionally).count();
                assertTrue(rejected >= 38);
                statement.execute("ROLLBACK");
            }
            int saved = 0;
            for (CompletableFuture<BindingRecord> write : writes) {
                if (write.isCompletedExceptionally()) {
                    failure(write, ErrorCode.BUSY);
                } else {
                    await(write);
                    saved++;
                }
            }
            assertTrue(saved >= 1 && saved <= 2);
        }
    }

    @Test
    void closeDrainsAcceptedWritesReleasesDatabaseAndRejectsFurtherWork() throws Exception {
        Path file = directory.resolve("data.db");
        BindingType type = type("qq", -1);
        SqliteBindingRepository repository = repository(file);
        await(repository.initialize(types(type)));
        List<CompletableFuture<BindingRecord>> writes = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            writes.add(repository.add(OWNER, Actor.console(), binding(type, "value" + i)));
        }
        repository.close();
        repository.close();
        for (CompletableFuture<BindingRecord> write : writes) {
            assertNotNull(await(write));
        }
        failure(repository.list(OWNER.uuid(), null, 1, 10), ErrorCode.UNAVAILABLE);
        try (SqliteBindingRepository reopened = repository(file)) {
            await(reopened.initialize(types(type)));
            assertEquals(30, await(reopened.list(OWNER.uuid(), null, 1, 100)).total());
        }
        Files.delete(file);
    }

    @Test
    void closesFromCompletionCallbackWithoutWaitingForItsOwnWorker() throws Exception {
        Path file = directory.resolve("data.db");
        BindingType type = type("qq", -1);
        SqliteBindingRepository repository = repository(file);
        try {
            await(repository.initialize(types(type)));
            CompletableFuture<Void> closed;
            try (Connection external = connect(file); Statement statement = external.createStatement()) {
                statement.execute("BEGIN IMMEDIATE");
                closed = repository.add(OWNER, Actor.console(), binding(type, "111111"))
                        .thenRun(repository::close);
                statement.execute("ROLLBACK");
            }
            closed.orTimeout(3, TimeUnit.SECONDS).join();
            failure(repository.list(OWNER.uuid(), null, 1, 10), ErrorCode.UNAVAILABLE);
        } finally {
            repository.close();
        }
    }

    @Test
    void closeHasBoundedWaitAndCancelsWorkThatNeverStarted() throws Exception {
        Path file = directory.resolve("data.db");
        BindingType type = type("qq", -1);
        SqliteBindingRepository repository = new SqliteBindingRepository(file, 256, 10_000);
        try {
            await(repository.initialize(types(type)));
            CompletableFuture<BindingRecord> running;
            CompletableFuture<BindingRecord> pending;
            try (Connection external = connect(file); Statement statement = external.createStatement()) {
                statement.execute("BEGIN IMMEDIATE");
                running = repository.add(OWNER, Actor.console(), binding(type, "111111"));
                pending = repository.add(OWNER, Actor.console(), binding(type, "222222"));
                long started = System.nanoTime();
                repository.close();
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 7500);
                failure(pending, ErrorCode.UNAVAILABLE);
                statement.execute("ROLLBACK");
            }
            // 已经开始的事务保留真实结果, 不提前宣称失败后再写入数据
            running.handle((record, failure) -> null).orTimeout(6, TimeUnit.SECONDS).join();
            failure(repository.rememberPlayer(OTHER), ErrorCode.UNAVAILABLE);
        } finally {
            repository.close();
        }
    }

    @Test
    void rejectsFutureSchemaVersionAndClosesFailedInitialization() throws Exception {
        Path file = directory.resolve("future.db");
        try (Connection external = connect(file); Statement statement = external.createStatement()) {
            statement.execute("PRAGMA user_version = 99");
        }
        try (SqliteBindingRepository repository = repository(file)) {
            failure(repository.initialize(types(type("qq", -1))), ErrorCode.INCOMPATIBLE_SCHEMA);
        }
        Files.delete(file);
    }

    private SqliteBindingRepository open(TypeRegistry registry) {
        SqliteBindingRepository repository = repository(directory.resolve("data.db"));
        await(repository.initialize(registry));
        return repository;
    }

    private SqliteBindingRepository repository(Path file) {
        return new SqliteBindingRepository(file, 256, 5000);
    }

    private static BindingType type(String id, int limit) {
        return new BindingType(id, 1, id.toUpperCase(), true, limit,
                List.of(field("value", "text", true, 100, Mask.PARTIAL)), List.of("value"));
    }

    private static FieldDefinition field(String id, String validator, boolean required, int length, Mask mask) {
        return new FieldDefinition(id, id, validator, required, length, mask);
    }

    private static TypeRegistry types(BindingType... types) {
        return new TypeRegistry(List.of(types));
    }

    private static ValidatedBinding binding(BindingType type, String value) {
        return new ValidatedBinding(type, Map.of("value", value), value);
    }

    private static Connection connect(Path file) throws Exception {
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + file);
    }

    private static <T> T await(CompletableFuture<T> future) {
        return future.orTimeout(15, TimeUnit.SECONDS).join();
    }

    private static BindingException failure(CompletableFuture<?> future, ErrorCode code) {
        CompletionException failure = assertThrows(CompletionException.class, () -> await(future));
        BindingException binding = assertInstanceOf(BindingException.class, failure.getCause());
        assertEquals(code, binding.code());
        return binding;
    }
}
