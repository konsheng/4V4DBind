package com.vvvvdddd.bind.core;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

public final class BindingService {
    private final BindingRepository repository;
    private final ValidatorRegistry validators;

    public BindingService(BindingRepository repository, ValidatorRegistry validators) {
        this.repository = Objects.requireNonNull(repository);
        this.validators = Objects.requireNonNull(validators);
    }

    public CompletableFuture<BindingRecord> add(PlayerIdentity owner, Actor actor, BindingType type,
                                               List<String> fields) {
        return safely(() -> {
            CoreChecks.require(owner != null && actor != null, ErrorCode.INVALID_INPUT);
            return repository.add(owner, actor, validators.normalizeAndValidate(type, fields));
        });
    }

    public CompletableFuture<BindingPage> list(UUID owner, String typeIdOrNull, int page, int pageSize) {
        return safely(() -> {
            owner(owner);
            if (typeIdOrNull != null) {
                CoreChecks.require(typeIdOrNull.matches("[a-z][a-z0-9_-]{0,31}"), ErrorCode.INVALID_INPUT);
            }
            CoreChecks.require(page >= 1 && page <= InputLimits.MAX_PAGE && pageSize >= 1
                    && pageSize <= InputLimits.MAX_PAGE_SIZE, ErrorCode.INVALID_INPUT);
            return repository.list(owner, typeIdOrNull, page, pageSize);
        });
    }

    public CompletableFuture<BindingRecord> view(UUID owner, long id) {
        return safely(() -> {
            owner(owner);
            CoreChecks.require(id >= 1, ErrorCode.INVALID_INPUT);
            return repository.view(owner, id);
        });
    }

    public CompletableFuture<Boolean> remove(UUID owner, long id) {
        return safely(() -> {
            owner(owner);
            CoreChecks.require(id >= 1, ErrorCode.INVALID_INPUT);
            return repository.remove(owner, id);
        });
    }

    private static void owner(UUID owner) {
        CoreChecks.require(owner != null, ErrorCode.INVALID_INPUT);
    }

    private static <T> CompletableFuture<T> safely(Supplier<CompletableFuture<T>> operation) {
        try {
            CompletableFuture<T> future = operation.get();
            if (future == null) {
                return CompletableFuture.failedFuture(new BindingException(ErrorCode.STORAGE_ERROR));
            }
            return future.handle((value, failure) -> {
                if (failure == null) {
                    return value;
                }
                Throwable cause = failure;
                for (int depth = 0; depth < 8 && (cause instanceof CompletionException
                        || cause instanceof ExecutionException) && cause.getCause() != null; depth++) {
                    cause = cause.getCause();
                }
                ErrorCode code = cause instanceof BindingException bindingException
                        ? bindingException.code() : ErrorCode.STORAGE_ERROR;
                throw new CompletionException(new BindingException(code));
            });
        } catch (BindingException exception) {
            return CompletableFuture.failedFuture(exception);
        } catch (RuntimeException exception) {
            // 外部实现抛出的异常可能携带资料, 只返回固定错误码
            return CompletableFuture.failedFuture(new BindingException(ErrorCode.STORAGE_ERROR));
        }
    }
}
