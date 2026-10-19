package com.vvvvdddd.bind.storage;

import com.google.gson.Gson;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.vvvvdddd.bind.core.Actor;
import com.vvvvdddd.bind.core.BindingException;
import com.vvvvdddd.bind.core.BindingPage;
import com.vvvvdddd.bind.core.BindingRecord;
import com.vvvvdddd.bind.core.BindingRepository;
import com.vvvvdddd.bind.core.BindingType;
import com.vvvvdddd.bind.core.ErrorCode;
import com.vvvvdddd.bind.core.FieldDefinition;
import com.vvvvdddd.bind.core.InputLimits;
import com.vvvvdddd.bind.core.PlayerIdentity;
import com.vvvvdddd.bind.core.TypeRegistry;
import com.vvvvdddd.bind.core.ValidatedBinding;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 单线程维护数据库连接, 所有磁盘操作都在后台执行 */
public final class SqliteBindingRepository implements BindingRepository {
    private static final int SCHEMA_VERSION = 1;
    private static final long CLOSE_WAIT_SECONDS = 5;
    private static final String RECORD_COLUMNS = "id, owner_uuid, type_id, type_version, values_json, "
            + "created_at, actor_kind, actor_uuid, length(CAST(values_json AS BLOB)) AS values_bytes";

    private final Path database;
    private final int busyTimeoutMillis;
    private final Gson gson = new Gson();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ThreadPoolExecutor executor;
    private volatile Connection connection;
    private volatile Thread storageThread;
    private TypeRegistry registry;

    public SqliteBindingRepository(Path database, int queueCapacity, int busyTimeoutMillis) {
        this.database = Objects.requireNonNull(database).toAbsolutePath().normalize();
        if (queueCapacity < 1 || busyTimeoutMillis < 0 || busyTimeoutMillis > 60_000) {
            throw new IllegalArgumentException("Invalid storage configuration");
        }
        this.busyTimeoutMillis = busyTimeoutMillis;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), runnable -> {
                    Thread thread = new Thread(runnable, "4V4DBind-storage");
                    thread.setDaemon(true);
                    storageThread = thread;
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy()) {
            @Override
            protected void terminated() {
                closeConnection();
            }
        };
    }

    @Override
    public CompletableFuture<Void> initialize(TypeRegistry types) {
        return submit(() -> {
            Objects.requireNonNull(types);
            if (connection != null) {
                synchronize(types);
                return null;
            }
            try {
                Path parent = database.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Class.forName("org.sqlite.JDBC");
                connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA busy_timeout = " + busyTimeoutMillis);
                    statement.execute("PRAGMA foreign_keys = ON");
                    statement.execute("PRAGMA journal_mode = WAL");
                }
                migrate();
                synchronize(types);
                return null;
            } catch (Exception | LinkageError failure) {
                registry = null;
                closeConnection();
                throw failure;
            }
        });
    }

    @Override
    public CompletableFuture<Void> synchronizeTypes(TypeRegistry types) {
        return submit(() -> {
            requireInitialized();
            synchronize(Objects.requireNonNull(types));
            return null;
        });
    }

    public CompletableFuture<String> diagnostics() {
        return submit(() -> {
            requireInitialized();
            try (Statement statement = connection.createStatement();
                 ResultSet row = statement.executeQuery("SELECT sqlite_version()")) {
                row.next();
                return "JDBC=" + connection.getMetaData().getDriverVersion() + ", SQLite=" + row.getString(1);
            }
        });
    }

    @Override
    public CompletableFuture<Void> rememberPlayer(PlayerIdentity player) {
        return submit(() -> {
            requireInitialized();
            upsertPlayer(Objects.requireNonNull(player));
            return null;
        });
    }

    @Override
    public CompletableFuture<PlayerIdentity> resolvePlayer(String uuidOrName) {
        return submit(() -> {
            requireInitialized();
            if (uuidOrName == null || uuidOrName.codePointCount(0, uuidOrName.length()) > 64
                    || bytes(uuidOrName) > 256) {
                throw new BindingException(ErrorCode.NOT_FOUND);
            }
            UUID uuid = parseUuid(uuidOrName);
            String sql = uuid == null
                    ? "SELECT uuid, name FROM players WHERE name_key = ? LIMIT 2"
                    : "SELECT uuid, name FROM players WHERE uuid = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uuid == null ? uuidOrName.toLowerCase(Locale.ROOT) : uuid.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new BindingException(ErrorCode.NOT_FOUND);
                    }
                    PlayerIdentity player;
                    try {
                        player = new PlayerIdentity(UUID.fromString(rows.getString("uuid")), rows.getString("name"));
                    } catch (RuntimeException failure) {
                        throw new BindingException(ErrorCode.STORAGE_ERROR);
                    }
                    if (rows.next()) {
                        throw new BindingException(ErrorCode.AMBIGUOUS_PLAYER);
                    }
                    return player;
                }
            }
        });
    }

    @Override
    public CompletableFuture<BindingRecord> add(PlayerIdentity owner, Actor actor, ValidatedBinding binding) {
        return submit(() -> {
            requireInitialized();
            Objects.requireNonNull(owner);
            Objects.requireNonNull(actor);
            Objects.requireNonNull(binding);
            BindingType type = registry.findId(binding.type().id())
                    .orElseThrow(() -> new BindingException(ErrorCode.UNKNOWN_TYPE));
            if (!type.enabled()) {
                throw new BindingException(ErrorCode.DISABLED_TYPE);
            }
            if (!structure(type).equals(structure(binding.type()))) {
                throw new BindingException(ErrorCode.INCOMPATIBLE_SCHEMA);
            }
            validateValues(type, binding.values(), ErrorCode.INVALID_INPUT);
            String json = gson.toJson(binding.values());
            if (bytes(json) > InputLimits.MAX_TOTAL_BYTES || binding.dedupeKey().isEmpty()
                    || bytes(binding.dedupeKey()) > InputLimits.MAX_TOTAL_BYTES) {
                throw new BindingException(ErrorCode.INVALID_INPUT);
            }
            return transaction(() -> {
                upsertPlayer(owner);
                // 先取得写锁, 再检查重复和限额, 防止多个连接同时通过检查
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT 1 FROM bindings WHERE owner_uuid = ? AND type_id = ? AND dedupe_key = ?")) {
                    statement.setString(1, owner.uuid().toString());
                    statement.setString(2, type.id());
                    statement.setString(3, binding.dedupeKey());
                    try (ResultSet rows = statement.executeQuery()) {
                        if (rows.next()) {
                            throw new BindingException(ErrorCode.DUPLICATE);
                        }
                    }
                }
                if (type.limit() != -1 && count(owner.uuid(), type.id()) >= type.limit()) {
                    throw new BindingException(ErrorCode.LIMIT_REACHED);
                }
                Instant createdAt = Instant.now();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO bindings
                            (owner_uuid, type_id, type_version, values_json, dedupe_key,
                             created_at, actor_kind, actor_uuid)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    statement.setString(1, owner.uuid().toString());
                    statement.setString(2, type.id());
                    statement.setInt(3, type.version());
                    statement.setString(4, json);
                    statement.setString(5, binding.dedupeKey());
                    statement.setString(6, createdAt.toString());
                    statement.setString(7, actor.kind());
                    statement.setString(8, actor.uuid() == null ? null : actor.uuid().toString());
                    statement.executeUpdate();
                }
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery("SELECT last_insert_rowid()")) {
                    rows.next();
                    return new BindingRecord(rows.getLong(1), owner.uuid(), type.id(), type.version(),
                            binding.values(), createdAt, actor);
                }
            });
        });
    }

    @Override
    public CompletableFuture<BindingPage> list(UUID owner, String typeIdOrNull, int page, int pageSize) {
        return submit(() -> {
            requireInitialized();
            Objects.requireNonNull(owner);
            if (page < 1 || page > InputLimits.MAX_PAGE || pageSize < 1 || pageSize > InputLimits.MAX_PAGE_SIZE) {
                throw new BindingException(ErrorCode.INVALID_INPUT);
            }
            if (typeIdOrNull != null && registry.findId(typeIdOrNull).isEmpty()) {
                throw new BindingException(ErrorCode.UNKNOWN_TYPE);
            }
            long total = count(owner, typeIdOrNull);
            String sql = "SELECT " + RECORD_COLUMNS + " FROM bindings WHERE owner_uuid = ?"
                    + (typeIdOrNull == null ? "" : " AND type_id = ?") + " ORDER BY id LIMIT ? OFFSET ?";
            List<BindingRecord> records = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, owner.toString());
                int parameter = 2;
                if (typeIdOrNull != null) {
                    statement.setString(parameter++, typeIdOrNull);
                }
                statement.setInt(parameter++, pageSize);
                statement.setLong(parameter, (long) (page - 1) * pageSize);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        records.add(readRecord(rows));
                    }
                }
            }
            return new BindingPage(records, total, page, pageSize);
        });
    }

    @Override
    public CompletableFuture<BindingRecord> view(UUID owner, long id) {
        return submit(() -> {
            requireInitialized();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + RECORD_COLUMNS + " FROM bindings WHERE owner_uuid = ? AND id = ?")) {
                statement.setString(1, Objects.requireNonNull(owner).toString());
                statement.setLong(2, id);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new BindingException(ErrorCode.NOT_FOUND);
                    }
                    return readRecord(rows);
                }
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> remove(UUID owner, long id) {
        return submit(() -> {
            requireInitialized();
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM bindings WHERE owner_uuid = ? AND id = ?")) {
                statement.setString(1, Objects.requireNonNull(owner).toString());
                statement.setLong(2, id);
                return statement.executeUpdate() == 1;
            }
        });
    }

    private void migrate() throws Exception {
        int version;
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA user_version")) {
            rows.next();
            version = rows.getInt(1);
        }
        if (version > SCHEMA_VERSION || version < 0) {
            throw new BindingException(ErrorCode.INCOMPATIBLE_SCHEMA);
        }
        if (version == 0) {
            transaction(() -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("""
                            CREATE TABLE IF NOT EXISTS players (
                                uuid TEXT PRIMARY KEY,
                                name TEXT NOT NULL,
                                name_key TEXT NOT NULL
                            )
                            """);
                    statement.execute("CREATE INDEX IF NOT EXISTS players_name_key ON players(name_key)");
                    statement.execute("""
                            CREATE TABLE IF NOT EXISTS binding_types (
                                id TEXT PRIMARY KEY,
                                type_version INTEGER NOT NULL,
                                structure_json TEXT NOT NULL
                            )
                            """);
                    statement.execute("""
                            CREATE TABLE IF NOT EXISTS bindings (
                                id INTEGER PRIMARY KEY AUTOINCREMENT,
                                owner_uuid TEXT NOT NULL REFERENCES players(uuid),
                                type_id TEXT NOT NULL REFERENCES binding_types(id),
                                type_version INTEGER NOT NULL,
                                values_json TEXT NOT NULL CHECK(length(CAST(values_json AS BLOB)) <= 8192),
                                dedupe_key TEXT NOT NULL,
                                created_at TEXT NOT NULL,
                                actor_kind TEXT NOT NULL,
                                actor_uuid TEXT,
                                UNIQUE(owner_uuid, type_id, dedupe_key)
                            )
                            """);
                    statement.execute("CREATE INDEX IF NOT EXISTS bindings_type_id ON bindings(type_id)");
                    statement.execute("PRAGMA user_version = " + SCHEMA_VERSION);
                }
                return null;
            });
        }
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (rows.next()) {
                throw new BindingException(ErrorCode.STORAGE_ERROR);
            }
        }
    }

    private void synchronize(TypeRegistry types) throws Exception {
        transaction(() -> {
            Map<String, String> current = new HashMap<>();
            for (BindingType type : types.all()) {
                current.put(type.id(), structure(type));
            }
            List<String> removed = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT id, structure_json FROM binding_types")) {
                while (rows.next()) {
                    String id = rows.getString("id");
                    String updated = current.get(id);
                    if (!Objects.equals(updated, rows.getString("structure_json"))) {
                        try (PreparedStatement count = connection.prepareStatement(
                                "SELECT 1 FROM bindings WHERE type_id = ? LIMIT 1")) {
                            count.setString(1, id);
                            try (ResultSet data = count.executeQuery()) {
                                if (data.next()) {
                                    throw new BindingException(ErrorCode.INCOMPATIBLE_SCHEMA);
                                }
                            }
                        }
                    }
                    if (updated == null) {
                        removed.add(id);
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM binding_types WHERE id = ?")) {
                for (String id : removed) {
                    statement.setString(1, id);
                    statement.executeUpdate();
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO binding_types(id, type_version, structure_json) VALUES (?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET type_version = excluded.type_version,
                        structure_json = excluded.structure_json
                    """)) {
                for (BindingType type : types.all()) {
                    statement.setString(1, type.id());
                    statement.setInt(2, type.version());
                    statement.setString(3, current.get(type.id()));
                    statement.executeUpdate();
                }
            }
            return null;
        });
        // 只有持久化事务成功后才切换内存中的类型定义
        registry = types;
    }

    private String structure(BindingType type) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (FieldDefinition field : type.fields()) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("id", field.id());
            values.put("validator", field.validator());
            values.put("required", field.required());
            values.put("maxLength", field.maxLength());
            fields.add(values);
        }
        Map<String, Object> signature = new LinkedHashMap<>();
        signature.put("version", type.version());
        signature.put("fields", fields);
        signature.put("dedupeFields", type.dedupeFields());
        return gson.toJson(signature);
    }

    private void upsertPlayer(PlayerIdentity player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO players(uuid, name, name_key) VALUES (?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, name_key = excluded.name_key
                """)) {
            statement.setString(1, player.uuid().toString());
            statement.setString(2, player.name());
            statement.setString(3, player.name().toLowerCase(Locale.ROOT));
            statement.executeUpdate();
        }
    }

    private long count(UUID owner, String typeId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM bindings WHERE owner_uuid = ?" + (typeId == null ? "" : " AND type_id = ?"))) {
            statement.setString(1, owner.toString());
            if (typeId != null) {
                statement.setString(2, typeId);
            }
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private BindingRecord readRecord(ResultSet rows) throws Exception {
        try {
            if (rows.getLong("values_bytes") > InputLimits.MAX_TOTAL_BYTES) {
                throw new BindingException(ErrorCode.STORAGE_ERROR);
            }
            String typeId = rows.getString("type_id");
            BindingType type = registry.findId(typeId)
                    .orElseThrow(() -> new BindingException(ErrorCode.STORAGE_ERROR));
            if (type.version() != rows.getInt("type_version")) {
                throw new BindingException(ErrorCode.STORAGE_ERROR);
            }
            Map<String, String> values = decodeValues(rows.getString("values_json"));
            validateValues(type, values, ErrorCode.STORAGE_ERROR);
            String actorUuid = rows.getString("actor_uuid");
            return new BindingRecord(rows.getLong("id"), UUID.fromString(rows.getString("owner_uuid")),
                    typeId, type.version(), values, Instant.parse(rows.getString("created_at")),
                    new Actor(rows.getString("actor_kind"), actorUuid == null ? null : UUID.fromString(actorUuid)));
        } catch (BindingException failure) {
            throw new BindingException(ErrorCode.STORAGE_ERROR);
        } catch (RuntimeException failure) {
            throw new BindingException(ErrorCode.STORAGE_ERROR);
        }
    }

    private Map<String, String> decodeValues(String json) throws Exception {
        if (json == null || bytes(json) > InputLimits.MAX_TOTAL_BYTES) {
            throw new BindingException(ErrorCode.STORAGE_ERROR);
        }
        Map<String, String> values = new LinkedHashMap<>();
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (values.size() >= InputLimits.MAX_FIELDS || values.containsKey(key)
                        || reader.peek() != JsonToken.STRING) {
                    throw new BindingException(ErrorCode.STORAGE_ERROR);
                }
                values.put(key, reader.nextString());
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new BindingException(ErrorCode.STORAGE_ERROR);
            }
        }
        return values;
    }

    private void validateValues(BindingType type, Map<String, String> values, ErrorCode code) {
        if (values.isEmpty() || values.size() > type.fields().size()) {
            throw new BindingException(code);
        }
        Map<String, FieldDefinition> fields = new HashMap<>();
        type.fields().forEach(field -> fields.put(field.id(), field));
        int codePoints = 0;
        int byteCount = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            FieldDefinition field = fields.get(entry.getKey());
            String value = entry.getValue();
            if (field == null || value == null || value.isBlank() || !safeText(value)
                    || value.codePointCount(0, value.length()) > field.maxLength()
                    || bytes(value) > InputLimits.MAX_FIELD_BYTES) {
                throw new BindingException(code);
            }
            codePoints += value.codePointCount(0, value.length());
            byteCount += bytes(value);
        }
        if (codePoints > InputLimits.MAX_TOTAL_CODE_POINTS || byteCount > InputLimits.MAX_TOTAL_BYTES) {
            throw new BindingException(code);
        }
        for (FieldDefinition field : type.fields()) {
            if (field.required() && (values.get(field.id()) == null || values.get(field.id()).isBlank())) {
                throw new BindingException(code);
            }
        }
    }

    private <T> T transaction(StorageWork<T> work) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
        }
        try {
            T result = work.execute();
            try (Statement statement = connection.createStatement()) {
                statement.execute("COMMIT");
            }
            return result;
        } catch (Exception | LinkageError failure) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ROLLBACK");
            } catch (SQLException ignored) {
                // 回滚失败时关闭连接, 禁止后续请求继续使用未知事务状态
                registry = null;
                closeConnection();
            }
            throw failure;
        }
    }

    private void requireInitialized() {
        if (connection == null || registry == null) {
            throw new BindingException(ErrorCode.UNAVAILABLE);
        }
    }

    private <T> CompletableFuture<T> submit(StorageWork<T> work) {
        RepositoryTask<T> task = new RepositoryTask<>(work);
        if (closed.get()) {
            task.reject(ErrorCode.UNAVAILABLE);
            return task.future;
        }
        try {
            executor.execute(task);
        } catch (RejectedExecutionException failure) {
            task.reject(closed.get() ? ErrorCode.UNAVAILABLE : ErrorCode.BUSY);
        }
        return task.future;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        if (Thread.currentThread() == storageThread) {
            return;
        }
        try {
            if (executor.awaitTermination(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)) {
                return;
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
        // 未开始的请求立即结束, 执行结束后由线程池终结钩子释放连接
        for (Runnable pending : executor.shutdownNow()) {
            if (pending instanceof RepositoryTask<?> task) {
                task.reject(ErrorCode.UNAVAILABLE);
            }
        }
    }

    private void closeConnection() {
        Connection current = connection;
        connection = null;
        if (current != null) {
            try {
                current.close();
            } catch (SQLException ignored) {
                // 关闭失败不回显驱动异常, 避免包含数据库原值
            }
        }
    }

    private static UUID parseUuid(String value) {
        if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            return null;
        }
        return UUID.fromString(value);
    }

    private static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static boolean safeText(String value) {
        return value.codePoints().noneMatch(codePoint -> {
            int category = Character.getType(codePoint);
            return category == Character.CONTROL || category == Character.FORMAT
                    || category == Character.LINE_SEPARATOR || category == Character.PARAGRAPH_SEPARATOR
                    || category == Character.SURROGATE || category == Character.PRIVATE_USE
                    || category == Character.UNASSIGNED;
        });
    }

    private static ErrorCode classify(Throwable failure) {
        if (failure instanceof BindingException binding) {
            return binding.code();
        }
        if (failure instanceof SQLException sql) {
            int primary = sql.getErrorCode() & 0xff;
            if (primary == 5 || primary == 6) {
                return ErrorCode.BUSY;
            }
        }
        return ErrorCode.STORAGE_ERROR;
    }

    @FunctionalInterface
    private interface StorageWork<T> {
        T execute() throws Exception;
    }

    private final class RepositoryTask<T> implements Runnable {
        private final StorageWork<T> work;
        private final CompletableFuture<T> future = new CompletableFuture<>();

        private RepositoryTask(StorageWork<T> work) {
            this.work = work;
        }

        @Override
        public void run() {
            try {
                future.complete(work.execute());
            } catch (Exception | LinkageError failure) {
                future.completeExceptionally(new BindingException(classify(failure)));
            }
        }

        private void reject(ErrorCode code) {
            future.completeExceptionally(new BindingException(code));
        }
    }
}
