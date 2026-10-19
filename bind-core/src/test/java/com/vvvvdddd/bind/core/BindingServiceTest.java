package com.vvvvdddd.bind.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BindingServiceTest {
    private final FakeRepository repository = new FakeRepository();
    private final BindingService service = new BindingService(repository, new ValidatorRegistry());
    private final PlayerIdentity owner = new PlayerIdentity(UUID.randomUUID(), "Player");

    @Test
    void validatesBeforeSendingNormalizedImmutableDataToStorage() {
        BindingRecord result = service.add(owner, Actor.player(owner.uuid()),
                ValidatorRegistryTest.single("email", 254), List.of("User@EXAMPLE.COM")).join();
        assertEquals("User@example.com", repository.lastBinding.values().get("value"));
        assertEquals(owner.uuid(), result.owner());
        assertEquals(1, repository.calls.get());
        assertThrows(UnsupportedOperationException.class, () -> repository.lastBinding.values().clear());
    }

    @Test
    void returnsFailedFuturesForAllInvalidServiceArguments() {
        assertCode(ErrorCode.INVALID_INPUT, service.add(owner, Actor.console(),
                ValidatorRegistryTest.single("qq", 12), List.of("invalid")));
        assertCode(ErrorCode.INVALID_INPUT, service.add(null, Actor.console(),
                ValidatorRegistryTest.single("qq", 12), List.of("12345")));
        assertCode(ErrorCode.UNKNOWN_TYPE, service.add(owner, Actor.console(), null, List.of("value")));
        assertCode(ErrorCode.INVALID_INPUT, service.list(owner.uuid(), null, 0, 10));
        assertCode(ErrorCode.INVALID_INPUT, service.list(owner.uuid(), null, Integer.MAX_VALUE, 10));
        assertCode(ErrorCode.INVALID_INPUT, service.list(owner.uuid(), null, 1, Integer.MAX_VALUE));
        assertCode(ErrorCode.INVALID_INPUT, service.list(owner.uuid(), "../table", 1, 10));
        assertCode(ErrorCode.INVALID_INPUT, service.view(owner.uuid(), -1));
        assertCode(ErrorCode.INVALID_INPUT, service.remove(null, 1));
        assertEquals(0, repository.calls.get());
    }

    @Test
    void preservesOwnershipInEveryRepositoryCall() {
        service.list(owner.uuid(), null, 1, 10).join();
        assertEquals(owner.uuid(), repository.lastOwner);
        service.view(owner.uuid(), Long.MAX_VALUE).join();
        assertEquals(owner.uuid(), repository.lastOwner);
        assertEquals(Long.MAX_VALUE, repository.lastId);
        assertTrue(service.remove(owner.uuid(), 12).join());
        assertEquals(owner.uuid(), repository.lastOwner);
        assertEquals(12, repository.lastId);
    }

    @Test
    void doesNotCompleteBeforeStorageCommitFutureCompletes() {
        repository.pending = new CompletableFuture<>();
        CompletableFuture<BindingRecord> result = service.add(owner, Actor.console(),
                ValidatorRegistryTest.single("qq", 12), List.of("12345"));
        assertFalse(result.isDone());
        BindingRecord record = repository.record(owner.uuid(), 1);
        repository.pending.complete(record);
        assertEquals(record, result.join());
    }

    @Test
    void preservesSafeBusinessErrorCodesFromAsynchronousStorage() {
        repository.pending = CompletableFuture.failedFuture(new CompletionException(new BindingException(ErrorCode.BUSY)));
        assertCode(ErrorCode.BUSY, service.add(owner, Actor.console(),
                ValidatorRegistryTest.single("qq", 12), List.of("12345")));
    }

    @Test
    void stripsSensitiveSynchronousAndAsynchronousRepositoryFailures() {
        String secret = "private-personal-data";
        repository.failure = new IllegalArgumentException(secret);
        CompletableFuture<BindingRecord> synchronous = service.view(owner.uuid(), 1);
        assertCode(ErrorCode.STORAGE_ERROR, synchronous);
        assertSanitized(synchronous, secret);
        repository.failure = null;
        repository.pending = CompletableFuture.failedFuture(new IllegalStateException(secret));
        CompletableFuture<BindingRecord> asynchronous = service.add(owner, Actor.console(),
                ValidatorRegistryTest.single("qq", 12), List.of("12345"));
        assertCode(ErrorCode.STORAGE_ERROR, asynchronous);
        assertSanitized(asynchronous, secret);
    }

    private static void assertCode(ErrorCode code, CompletableFuture<?> future) {
        CompletionException exception = assertThrows(CompletionException.class, future::join);
        assertInstanceOf(BindingException.class, exception.getCause());
        assertEquals(code, ((BindingException) exception.getCause()).code());
    }

    private static void assertSanitized(CompletableFuture<?> future, String secret) {
        CompletionException exception = assertThrows(CompletionException.class, future::join);
        assertFalse(exception.toString().contains(secret));
        assertFalse(exception.getCause().toString().contains(secret));
        assertNull(exception.getCause().getCause());
    }

    private static final class FakeRepository implements BindingRepository {
        private final AtomicInteger calls = new AtomicInteger();
        private ValidatedBinding lastBinding;
        private UUID lastOwner;
        private long lastId;
        private CompletableFuture<BindingRecord> pending;
        private RuntimeException failure;

        @Override
        public CompletableFuture<Void> initialize(TypeRegistry registry) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> synchronizeTypes(TypeRegistry registry) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> rememberPlayer(PlayerIdentity player) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<PlayerIdentity> resolvePlayer(String uuidOrName) {
            return CompletableFuture.failedFuture(new BindingException(ErrorCode.NOT_FOUND));
        }

        @Override
        public CompletableFuture<BindingRecord> add(PlayerIdentity player, Actor actor, ValidatedBinding binding) {
            calls.incrementAndGet();
            lastBinding = binding;
            return pending != null ? pending : CompletableFuture.completedFuture(record(player.uuid(), 1));
        }

        @Override
        public CompletableFuture<BindingPage> list(UUID player, String typeIdOrNull, int page, int pageSize) {
            calls.incrementAndGet();
            lastOwner = player;
            return CompletableFuture.completedFuture(new BindingPage(List.of(), 0, page, pageSize));
        }

        @Override
        public CompletableFuture<BindingRecord> view(UUID player, long id) {
            calls.incrementAndGet();
            lastOwner = player;
            lastId = id;
            if (failure != null) {
                throw failure;
            }
            return CompletableFuture.completedFuture(record(player, id));
        }

        @Override
        public CompletableFuture<Boolean> remove(UUID player, long id) {
            calls.incrementAndGet();
            lastOwner = player;
            lastId = id;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public void close() {
        }

        private BindingRecord record(UUID player, long id) {
            ValidatedBinding binding = lastBinding != null ? lastBinding : new ValidatorRegistry()
                    .normalizeAndValidate(ValidatorRegistryTest.single("qq", 12), List.of("12345"));
            return new BindingRecord(id, player, binding.type().id(), binding.values(),
                    Instant.EPOCH, Actor.console());
        }
    }
}
