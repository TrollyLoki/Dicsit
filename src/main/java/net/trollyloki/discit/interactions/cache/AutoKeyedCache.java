package net.trollyloki.discit.interactions.cache;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

@NullMarked
public class AutoKeyedCache<T> {

    private final InteractionDataCache<UUID, T> cache = new InteractionDataCache<>();

    public UUID put(T value) {
        UUID key;
        do {
            key = UUID.randomUUID();
        } while (!cache.putIfAbsent(key, value));
        return key;
    }

    public @Nullable T pop(UUID key) {
        return cache.pop(key);
    }

}
