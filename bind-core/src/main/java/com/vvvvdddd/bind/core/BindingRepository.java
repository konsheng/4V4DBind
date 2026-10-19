package com.vvvvdddd.bind.core;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface BindingRepository extends AutoCloseable {
    CompletableFuture<Void> initialize(TypeRegistry registry);

    CompletableFuture<Void> synchronizeTypes(TypeRegistry registry);

    CompletableFuture<Void> rememberPlayer(PlayerIdentity player);

    CompletableFuture<PlayerIdentity> resolvePlayer(String uuidOrName);

    CompletableFuture<BindingRecord> add(PlayerIdentity owner, Actor actor, ValidatedBinding binding);

    CompletableFuture<BindingPage> list(UUID owner, String typeIdOrNull, int page, int pageSize);

    CompletableFuture<BindingRecord> view(UUID owner, long id);

    CompletableFuture<Boolean> remove(UUID owner, long id);

    @Override
    void close();
}
