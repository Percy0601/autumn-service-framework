package com.microapp.autumn.core.server;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 给 provider worker 加请求处理超时（{@code provider.timeout}，单位秒）。
 *
 * <p>每个提交的 worker task 挂一个定时守卫，超时后中断执行线程并取消任务，
 * 避免慢服务长期占住 worker 池；正常完成时守卫立即取消，不产生堆积。</p>
 */
public final class RequestTimeoutExecutor extends AbstractExecutorService {

    private static final Logger log = LoggerFactory.getLogger(RequestTimeoutExecutor.class);

    private final ExecutorService delegate;
    private final ScheduledExecutorService timer;
    private final long timeoutMillis;

    public RequestTimeoutExecutor(ExecutorService delegate, ScheduledExecutorService timer, long timeoutSeconds) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.timeoutMillis = timeoutSeconds <= 0 ? 0 : timeoutSeconds * 1000L;
    }

    @Override
    public void execute(Runnable command) {
        if (timeoutMillis <= 0 || timer.isShutdown()) {
            delegate.execute(command);
            return;
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        Future<?> future = delegate.submit(() -> {
            try {
                command.run();
            } finally {
                done.complete(null);
            }
        });
        ScheduledFuture<?> guard = timer.schedule(() -> {
            // complete 返回 true 表示是超时分支首次完成，正常完成分支会抢先完成
            if (done.complete(null)) {
                future.cancel(true);
                log.warn("autumn provider request exceeded {}ms, worker interrupted", timeoutMillis);
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);
        done.whenComplete((ignored, error) -> guard.cancel(false));
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}
