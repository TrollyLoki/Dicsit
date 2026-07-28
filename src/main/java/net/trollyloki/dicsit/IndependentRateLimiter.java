package net.trollyloki.dicsit;

import net.dv8tion.jda.api.exceptions.RateLimitedException;
import net.dv8tion.jda.api.requests.RestAction;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@NullMarked
public class IndependentRateLimiter<T> {

    private static final Logger LOGGER = LoggerFactory.getLogger(IndependentRateLimiter.class);

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final CompletableFuture<T> future = new CompletableFuture<>();
    private final RestAction<T> action;

    private final Map<String, String> mdc;

    private IndependentRateLimiter(RestAction<T> action) {
        this.action = action;

        mdc = MDC.getCopyOfContextMap();

        executor.submit(this::execute);
        future.whenCompleteAsync((_, _) -> executor.close(), executor);
    }

    private void execute() {
        try {
            MDC.setContextMap(mdc);

            try {
                future.complete(action.complete(false));
            } catch (RateLimitedException e) {
                executor.schedule(this::execute, e.getRetryAfter(), TimeUnit.MILLISECONDS);
                LOGGER.warn("Rate limited! Retrying in {} seconds", e.getRetryAfter() / 1000);
            }

        } catch (Exception e) {
            future.completeExceptionally(e);
        }
    }

    public static <T> CompletableFuture<T> submit(RestAction<T> action) {
        return new IndependentRateLimiter<>(action).future;
    }

}
