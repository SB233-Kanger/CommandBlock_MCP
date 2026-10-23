package xzh.studio.commblock.mcp;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** A timeout can win against a queued task, but never cancel a world mutation midway. */
public final class WorldRequests implements AutoCloseable {
    private final Consumer<Runnable> executor;
    private final Set<Task<?>> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    public WorldRequests(Consumer<Runnable> executor) { this.executor = executor; }
    public <T> T call(Callable<T> action, int timeoutSeconds) throws Exception {
        Task<T> task = new Task<>();
        pending.add(task);
        if (closed) task.cancel();
        else try { executor.accept(() -> {
            if (closed) task.cancel();
            if (task.state.compareAndSet(0, 1)) {
                try { task.result.complete(action.call()); }
                catch (Throwable e) { task.result.completeExceptionally(e); }
                finally { pending.remove(task); }
            }
        }); } catch (RuntimeException e) { task.cancel(); pending.remove(task); throw e; }
        try { return task.result.get(timeoutSeconds, TimeUnit.SECONDS); }
        catch (TimeoutException e) {
            if (task.cancel()) throw new TimeoutException("World request timed out before starting; cancelled");
            // Already started: return its actual outcome, rather than reporting a cancelled write.
            return task.result.get();
        } catch (InterruptedException e) {
            task.cancel(); Thread.currentThread().interrupt(); throw e;
        } finally { if (task.state.get() == 2) pending.remove(task); }
    }
    public void close() { closed = true; pending.forEach(Task::cancel); }
    private static final class Task<T> {
        final AtomicInteger state = new AtomicInteger();
        final CompletableFuture<T> result = new CompletableFuture<>();
        boolean cancel() {
            if (!state.compareAndSet(0, 2)) return false;
            result.completeExceptionally(new CancellationException("World request cancelled"));
            return true;
        }
    }
}
