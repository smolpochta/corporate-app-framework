/** Менеджер фоновых задач,
 * версия с поддержкой:
 * - приоритетов задач;
 * - таймаутов выполнения;
 * - экспоненциальной backoff-политики повторных попыток;
 * - circuit Breaker для защиты от каскадных сбоев;
 * - мониторинга здоровья пула потоков;
 * - graceful shutdown;
 *
 * автор: Алексей <smolpochta@gmail.com> 2025
 * лицензия: MIT
 */

package com.smolpochta.decanter;

import android.util.Log;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.lang.ref.WeakReference;

/**
 * Менеджер для управления фоновыми задачами приложения
 *
 * Особенности:
 * 1. Поддержка приоритетов (HIGH, MEDIUM, LOW)
 * 2. Автоматическое пересоздание dead потоков
 * 3. Таймауты выполнения для каждой задачи
 * 4. Экспоненциальная backoff-политика повторных попыток
 * 5. Circuit Breaker для защиты от каскадных сбоев
 * 6. Детальный мониторинг и метрики
 * 7. Graceful shutdown с сохранением состояния
 */
public class BackgroundTaskManager {
    private static final String TAG = "BackgroundTaskManager";

    /** Интервал проверки задач в миллисекундах */
    private static final long DEFAULT_CHECK_INTERVAL_MS = 100;

    /** Максимальное количество потоков в пуле */
    private static final int MAX_POOL_SIZE = 4;

    /** Core pool size (постоянно активные потоки) */
    private static final int CORE_POOL_SIZE = 4;

    /** Время простоя потока перед завершением (секунды) */
    private static final long THREAD_KEEP_ALIVE_TIME_SECONDS = 60;

    /** Размер очереди ожидания задач */
    private static final int DEFAULT_TASK_QUEUE_SIZE = 100;

    /** Таймаут по умолчанию для задач (секунды) */
    private static final long DEFAULT_TASK_TIMEOUT_SECONDS = 30;

    /** Интервал мониторинга здоровья пула (секунды) */
    private static final long POOL_MONITOR_INTERVAL_SECONDS = 30;

    /** Таймаут graceful shutdown (секунды) */
    private static final long GRACEFUL_SHUTDOWN_TIMEOUT_SECONDS = 10;


    /**
     * Приоритет задачи
     * HIGH - критические задачи (например, сохранение данных)
     * MEDIUM - обычные фоновые задачи (синхронизация)
     * LOW - задачи низкой важности (очистка кэша, логирование)
     */
    public enum TaskPriority {
        HIGH(0),
        MEDIUM(1),
        LOW(2);

        private final int value;

        TaskPriority(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    /**
     * Состояние Circuit Breaker
     */
    private enum CircuitBreakerState {
        CLOSED,     // Работает нормально
        OPEN,       // Открыт (все запросы блокируются)
        HALF_OPEN   // Полуоткрыт (тестовые запросы разрешены)
    }

    /**
     * Результат выполнения задачи
     */
    public enum TaskResult {
        SUCCESS,
        FAILURE,
        TIMEOUT,
        CANCELLED
    }

    /**
     * Политика повторных попыток
     */
    public static class RetryPolicy {
        private final int maxAttempts;
        private final long initialDelayMs;
        private final long maxDelayMs;
        private final double backoffMultiplier;
        private final Set<Class<? extends Throwable>> retryableExceptions;

        public RetryPolicy(int maxAttempts, long initialDelayMs, long maxDelayMs,
                           double backoffMultiplier,
                           Set<Class<? extends Throwable>> retryableExceptions) {
            this.maxAttempts = maxAttempts;
            this.initialDelayMs = initialDelayMs;
            this.maxDelayMs = maxDelayMs;
            this.backoffMultiplier = backoffMultiplier;
            this.retryableExceptions = retryableExceptions != null ?
                    retryableExceptions : Collections.emptySet();
        }

        /**
         * Следует ли повторять попытку для данного исключения
         */
        public boolean shouldRetry(Throwable t, int attempt) {
            if (attempt >= maxAttempts) {
                return false;
            }

            // Если список исключений пуст - повторяем для всех
            if (retryableExceptions.isEmpty()) {
                return true;
            }

            // Проверяем, является ли исключение retryable
            return retryableExceptions.stream()
                    .anyMatch(cls -> cls.isAssignableFrom(t.getClass()));
        }

        /**
         * Вычисляет задержку для следующей попытки
         */
        public long getDelayMs(int attempt) {
            if (attempt <= 1) {
                return initialDelayMs;
            }

            long delay = (long)(initialDelayMs * Math.pow(backoffMultiplier, attempt - 1));
            return Math.min(delay, maxDelayMs);
        }

        public static RetryPolicy defaultPolicy() {
            return new RetryPolicy(
                    3,              // 3 попытки
                    1000,           // начальная задержка 1 секунда
                    30000,          // максимальная задержка 30 секунд
                    2.0,            // экспоненциальный backoff
                    new HashSet<>(Arrays.asList(
                            IOException.class,
                            SocketTimeoutException.class,
                            ConnectException.class
                    ))
            );
        }
    }

    /**
     * Circuit Breaker для защиты от каскадных сбоев
     */
    private static class CircuitBreaker {
        private volatile CircuitBreakerState state = CircuitBreakerState.CLOSED;
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private final AtomicInteger successCount = new AtomicInteger(0);
        private volatile long lastFailureTime = 0;

        private final int failureThreshold;
        private final long resetTimeoutMs;
        private final int successThreshold;

        public CircuitBreaker(int failureThreshold, long resetTimeoutMs, int successThreshold) {
            this.failureThreshold = failureThreshold;
            this.resetTimeoutMs = resetTimeoutMs;
            this.successThreshold = successThreshold;
        }

        /**
         * Проверяет, разрешено ли выполнение
         */
        public synchronized boolean allowExecution() {
            switch (state) {
                case CLOSED:
                    return true;

                case OPEN:
                    // Проверяем, не истёк ли таймаут для перехода в HALF_OPEN
                    if (System.currentTimeMillis() - lastFailureTime > resetTimeoutMs) {
                        state = CircuitBreakerState.HALF_OPEN;
                        successCount.set(0);
                        return true; // Разрешаем тестовый запрос
                    }
                    return false;

                case HALF_OPEN:
                    return true;

                default:
                    return false;
            }
        }

        /**
         * Уведомляет об успешном выполнении
         */
        public synchronized void onSuccess() {
            if (state == CircuitBreakerState.HALF_OPEN) {
                successCount.incrementAndGet();
                if (successCount.get() >= successThreshold) {
                    // Достаточно успешных выполнений - закрываем Circuit Breaker
                    state = CircuitBreakerState.CLOSED;
                    failureCount.set(0);
                    successCount.set(0);
                }
            } else {
                // В CLOSED состоянии сбрасываем счётчик неудач
                failureCount.set(0);
            }
        }

        /**
         * Уведомляет о неудачном выполнении
         */
        public synchronized void onFailure() {
            failureCount.incrementAndGet();
            lastFailureTime = System.currentTimeMillis();

            if (state == CircuitBreakerState.CLOSED &&
                    failureCount.get() >= failureThreshold) {
                // Превышен порог неудач - открываем Circuit Breaker
                state = CircuitBreakerState.OPEN;
            } else if (state == CircuitBreakerState.HALF_OPEN) {
                // В HALF_OPEN состоянии неудача возвращает в OPEN
                state = CircuitBreakerState.OPEN;
                successCount.set(0);
            }
        }

        public CircuitBreakerState getState() {
            return state;
        }

        public int getFailureCount() {
            return failureCount.get();
        }
    }

    /**
     * Внутренний класс, представляющий фоновую задачу
     */
    public static class BackgroundTask {
        public final String functionName;
        public final Object parameters;
        public final long interval;
        public final TaskPriority priority;
        public final long timeoutMs;
        public final RetryPolicy retryPolicy;
        public final CircuitBreaker circuitBreaker;

        public volatile long nextExecutionTime;
        public volatile long lastExecutionTime;
        public volatile boolean isScheduled;
        public volatile int executionAttempts;
        public final String taskId;

        public BackgroundTask(String functionName, Object parameters, long interval,
                              TaskPriority priority, long timeoutMs,
                              RetryPolicy retryPolicy, CircuitBreaker circuitBreaker) {
            this.functionName = functionName;
            this.parameters = parameters;
            this.interval = interval;
            this.priority = (priority != null) ? priority : TaskPriority.MEDIUM;
            this.timeoutMs = (timeoutMs > 0) ? timeoutMs : DEFAULT_TASK_TIMEOUT_SECONDS * 1000;
            this.retryPolicy = (retryPolicy != null) ? retryPolicy : RetryPolicy.defaultPolicy();
            this.circuitBreaker = (circuitBreaker != null) ? circuitBreaker :
                    new CircuitBreaker(5, 30000, 3);

            this.nextExecutionTime = System.currentTimeMillis();
            this.lastExecutionTime = 0;
            this.isScheduled = false;
            this.executionAttempts = 0;
            this.taskId = generateTaskId(functionName);
        }

        private String generateTaskId(String functionName) {
            return functionName + "_" + System.currentTimeMillis() + "_" +
                    ThreadLocalRandom.current().nextInt(1000, 9999);
        }

        public boolean shouldExecuteNow() {
            return !isScheduled && System.currentTimeMillis() >= nextExecutionTime;
        }

        public void markScheduled() {
            this.isScheduled = true;
            this.executionAttempts = 0;
        }

        public void markCompleted() {
            this.isScheduled = false;
            this.executionAttempts = 0;
            this.lastExecutionTime = System.currentTimeMillis();

            if (this.interval > 0) {
                this.nextExecutionTime = this.lastExecutionTime + this.interval;
            } else {
                this.nextExecutionTime = Long.MAX_VALUE;
            }
        }

        public void markFailed() {
            this.isScheduled = false;
            this.executionAttempts++;

            if (this.interval > 0) {
                // Для периодических задач откладываем следующее выполнение
                long delay = retryPolicy.getDelayMs(this.executionAttempts);
                this.nextExecutionTime = System.currentTimeMillis() + delay;
            }
        }
    }

    // ============ КОЛЛЕКЦИИ ДЛЯ УПРАВЛЕНИЯ ЗАДАЧАМИ ============

    /**
     * Очередь с приоритетами для планирования задач
     * Сортировка по:
     * 1. Приоритету (HIGH -> MEDIUM -> LOW)
     * 2. Времени выполнения (раньше -> позже)
     */
    private final PriorityBlockingQueue<BackgroundTask> taskQueue =
            new PriorityBlockingQueue<>(DEFAULT_TASK_QUEUE_SIZE,
                    Comparator.comparing((BackgroundTask t) -> t.priority.getValue())
                            .thenComparing(t -> t.nextExecutionTime));

    /**
     * Быстрый доступ к задачам по имени
     */
    private final ConcurrentHashMap<String, BackgroundTask> taskMap =
            new ConcurrentHashMap<>();

    /**
     * Реестр функций для выполнения
     */
    private final ConcurrentHashMap<String, Runnable> functionRegistry =
            new ConcurrentHashMap<>();

    /**
     * Отслеживание выполняющихся задач
     */
    private final ConcurrentHashMap<String, Future<TaskResult>> runningTasks =
            new ConcurrentHashMap<>();

    /**
     * Circuit Breaker для каждой функции
     */
    private final ConcurrentHashMap<String, CircuitBreaker> circuitBreakers =
            new ConcurrentHashMap<>();

    /**
     * Метрики выполнения задач
     */
    private final MetricsCollector metrics = new MetricsCollector();

    // ============ ИСПОЛНИТЕЛИ ПОТОКОВ ============

    private ScheduledExecutorService scheduler;
    private ThreadPoolExecutor taskExecutor;
    private ScheduledExecutorService monitorExecutor;

    // ============ СИНХРОНИЗАЦИЯ ============

    private final ReadWriteLock taskLock = new ReentrantReadWriteLock();
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);

    // ============ ФАБРИКИ ПОТОКОВ ============

    /**
     * Фабрика потоков с защитой от тихого падения
     */
    private static class SafeThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);
        private final String namePrefix;
        private final WeakReference<ThreadPoolExecutor> executorRef;

        public SafeThreadFactory(String namePrefix, ThreadPoolExecutor executor) {
            this.namePrefix = namePrefix;
            this.executorRef = new WeakReference<>(executor);
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(() -> {
                try {
                    r.run();
                } catch (Throwable t) {
                    Log.e("SafeThreadFactory",
                            "Uncaught exception in " + Thread.currentThread().getName(), t);
                    // Пересоздаём поток, если он умер
                    recreateThreadIfNeeded();
                    throw t;
                }
            });

            thread.setName(namePrefix + "-" + counter.getAndIncrement());
            thread.setUncaughtExceptionHandler((t, e) -> {
                Log.e("SafeThreadFactory",
                        "Thread " + t.getName() + " died with exception", e);
                recreateThreadIfNeeded();
            });

            return thread;
        }

        private void recreateThreadIfNeeded() {
            ThreadPoolExecutor executor = executorRef.get();
            if (executor != null && !executor.isShutdown() &&
                    executor.getPoolSize() < executor.getMaximumPoolSize()) {
                executor.prestartCoreThread();
            }
        }
    }

    /**
     * Обработчик переполненной очереди с приоритетами
     */
    private static class PriorityRejectionHandler implements RejectedExecutionHandler {
        private final PriorityBlockingQueue<Runnable> overflowQueue =
                new PriorityBlockingQueue<>(100,
                        Comparator.comparingInt((Runnable r) -> {
                            if (r instanceof PriorityRunnable) {
                                return ((PriorityRunnable) r).getPriority().getValue();
                            }
                            return TaskPriority.MEDIUM.getValue();
                        }));

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            if (executor.isShutdown()) {
                return;
            }

            // Сохраняем задачу в overflow очередь
            overflowQueue.offer(r);

            // Пытаемся выполнить переполненные задачи позже
            if (!executor.isShutdown()) {
                executor.execute(() -> {
                    Runnable overflowTask = overflowQueue.poll();
                    if (overflowTask != null) {
                        overflowTask.run();
                    }
                });
            }
        }
    }

    /**
     * Runnable с поддержкой приоритетов
     */
    private static class PriorityRunnable implements Runnable {
        private final Runnable delegate;
        private final TaskPriority priority;

        public PriorityRunnable(Runnable delegate, TaskPriority priority) {
            this.delegate = delegate;
            this.priority = priority;
        }

        public TaskPriority getPriority() {
            return priority;
        }

        @Override
        public void run() {
            delegate.run();
        }
    }

    // ============ МЕТРИКИ И МОНИТОРИНГ ============

    /**
     * Сборщик метрик выполнения задач
     */
    private static class MetricsCollector {
        private final AtomicLong tasksCompleted = new AtomicLong();
        private final AtomicLong tasksFailed = new AtomicLong();
        private final AtomicLong tasksTimeout = new AtomicLong();
        private final AtomicLong totalExecutionTime = new AtomicLong();
        private final ConcurrentHashMap<String, AtomicLong> taskTypeCounts =
                new ConcurrentHashMap<>();

        public void recordSuccess(String taskName, long executionTime) {
            tasksCompleted.incrementAndGet();
            totalExecutionTime.addAndGet(executionTime);
            taskTypeCounts.computeIfAbsent(taskName, k -> new AtomicLong()).incrementAndGet();
        }

        public void recordFailure(String taskName) {
            tasksFailed.incrementAndGet();
        }

        public void recordTimeout(String taskName) {
            tasksTimeout.incrementAndGet();
        }

        public MetricsSnapshot getSnapshot() {

            Map<String, Long> convertedCounts = new HashMap<>();
            for (Map.Entry<String, AtomicLong> entry : taskTypeCounts.entrySet()) {
                convertedCounts.put(entry.getKey(), entry.getValue().get());
            }

            return new MetricsSnapshot(
                    tasksCompleted.get(),
                    tasksFailed.get(),
                    tasksTimeout.get(),
                    totalExecutionTime.get(),
                    convertedCounts
            );
        }

        public static class MetricsSnapshot {
            public final long tasksCompleted;
            public final long tasksFailed;
            public final long tasksTimeout;
            public final long totalExecutionTimeMs;
            public final Map<String, Long> taskTypeCounts;

            public MetricsSnapshot(long completed, long failed, long timeout,
                                   long totalTime, Map<String, Long> typeCounts) {
                this.tasksCompleted = completed;
                this.tasksFailed = failed;
                this.tasksTimeout = timeout;
                this.totalExecutionTimeMs = totalTime;
                this.taskTypeCounts = typeCounts;
            }

            public double getAverageExecutionTime() {
                return tasksCompleted > 0 ?
                        (double) totalExecutionTimeMs / tasksCompleted : 0.0;
            }

            public double getSuccessRate() {
                long total = tasksCompleted + tasksFailed + tasksTimeout;
                return total > 0 ? (double) tasksCompleted / total * 100 : 0.0;
            }
        }
    }

    /**
     * Мониторинг здоровья пула потоков
     */
    private class PoolHealthMonitor implements Runnable {
        @Override
        public void run() {
            if (taskExecutor == null || taskExecutor.isShutdown()) {
                return;
            }

            try {
                logPoolStats();
                checkForDeadThreads();
                checkQueueHealth();
                logMetrics();
            } catch (Exception e) {
                Log.w(TAG, "Error in pool health monitor", e);
            }
        }

        private void logPoolStats() {
            Log.d(TAG, String.format(
                    "Pool stats: active=%d, queue=%d, completed=%d, poolSize=%d/%d",
                    taskExecutor.getActiveCount(),
                    taskExecutor.getQueue().size(),
                    taskExecutor.getCompletedTaskCount(),
                    taskExecutor.getPoolSize(),
                    taskExecutor.getMaximumPoolSize()
            ));
        }

        private void checkForDeadThreads() {
            // Если есть задачи в очереди, но нет активных потоков,
            // возможно потоки умерли - пересоздаём
            if (taskExecutor.getActiveCount() == 0 &&
                    taskExecutor.getQueue().size() > 0 &&
                    taskExecutor.getPoolSize() < taskExecutor.getMaximumPoolSize()) {

                Log.w(TAG, "Possible dead threads detected, recreating core threads");
                taskExecutor.prestartAllCoreThreads();
            }
        }

        private void checkQueueHealth() {
            int queueSize = taskExecutor.getQueue().size();
            if (queueSize > DEFAULT_TASK_QUEUE_SIZE * 0.8) {
                Log.w(TAG, "Task queue is 80% full (" + queueSize + " tasks)");
            }
        }

        private void logMetrics() {
            MetricsCollector.MetricsSnapshot snapshot = metrics.getSnapshot();
            if (snapshot.tasksCompleted > 0) {
                Log.d(TAG, String.format(
                        "Metrics: completed=%d, failed=%d, timeout=%d, successRate=%.1f%%, avgTime=%.1fms",
                        snapshot.tasksCompleted,
                        snapshot.tasksFailed,
                        snapshot.tasksTimeout,
                        snapshot.getSuccessRate(),
                        snapshot.getAverageExecutionTime()
                ));
            }
        }
    }

    // ============ ПУБЛИЧНЫЕ МЕТОДЫ ============

    /**
     * Инициализация менеджера фоновых задач
     */
    public void initialize() {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "Manager already initialized");
            return;
        }

        Log.i(TAG, "Initializing BackgroundTaskManager...");

        try {
            // Создаём планировщик
            scheduler = Executors.newSingleThreadScheduledExecutor(
                    new SafeThreadFactory("TaskScheduler", null));

            // Создаём пул потоков с приоритетами
            taskExecutor = new ThreadPoolExecutor(
                    CORE_POOL_SIZE,
                    MAX_POOL_SIZE,
                    THREAD_KEEP_ALIVE_TIME_SECONDS,
                    TimeUnit.SECONDS,
                    new PriorityBlockingQueue<>(10,  // Маленькая очередь для приоритетов
                            Comparator.comparing((Runnable r) -> {
                                if (r instanceof PriorityRunnable) {
                                    return ((PriorityRunnable) r).getPriority().getValue();
                                }
                                return TaskPriority.MEDIUM.getValue();
                            })),
                    new SafeThreadFactory("TaskExecutor", null),
                    new PriorityRejectionHandler()
            );

            // Создаём монитор здоровья
            monitorExecutor = Executors.newSingleThreadScheduledExecutor(
                    new SafeThreadFactory("PoolMonitor", null));

            // Запускаем периодические задачи
            scheduler.scheduleWithFixedDelay(
                    this::processTasks,
                    0,
                    DEFAULT_CHECK_INTERVAL_MS,
                    TimeUnit.MILLISECONDS
            );

            // Запускаем мониторинг здоровья пула
            monitorExecutor.scheduleWithFixedDelay(
                    new PoolHealthMonitor(),
                    0,
                    POOL_MONITOR_INTERVAL_SECONDS,
                    TimeUnit.SECONDS
            );

            Log.i(TAG, "BackgroundTaskManager initialized successfully");

        } catch (Exception e) {
            isRunning.set(false);
            Log.e(TAG, "Failed to initialize BackgroundTaskManager", e);
            throw new RuntimeException("BackgroundTaskManager initialization failed", e);
        }
    }

    /**
     * Graceful shutdown менеджера
     */
    public void shutdown() {
        if (!isRunning.get() || isShuttingDown.getAndSet(true)) {
            return;
        }

        Log.i(TAG, "Starting graceful shutdown...");

        try {
            // 1. Останавливаем планировщик
            if (scheduler != null) {
                scheduler.shutdown();
            }

            // 2. Останавливаем монитор
            if (monitorExecutor != null) {
                monitorExecutor.shutdown();
            }

            // 3. Останавливаем исполнитель задач (gracefully)
            if (taskExecutor != null) {
                taskExecutor.shutdown();

                // Даём время на завершение текущих задач
                if (!taskExecutor.awaitTermination(
                        GRACEFUL_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {

                    Log.w(TAG, "Forcing shutdown after timeout");
                    List<Runnable> pendingTasks = taskExecutor.shutdownNow();
                    Log.w(TAG, "Cancelled " + pendingTasks.size() + " pending tasks");

                    // Ждём окончательного завершения
                    taskExecutor.awaitTermination(5, TimeUnit.SECONDS);
                }
            }

            // 4. Отменяем все выполняющиеся задачи
            for (Future<TaskResult> future : runningTasks.values()) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }

            // 5. Очищаем коллекции
            taskQueue.clear();
            taskMap.clear();
            runningTasks.clear();

            isRunning.set(false);
            isShuttingDown.set(false);

            Log.i(TAG, "BackgroundTaskManager shutdown completed");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "Shutdown interrupted", e);

            // Принудительный shutdown
            forceShutdown();
        } catch (Exception e) {
            Log.e(TAG, "Error during shutdown", e);
            forceShutdown();
        }
    }

    /**
     * Принудительный shutdown (использовать только в крайнем случае)
     */
    private void forceShutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (monitorExecutor != null) {
            monitorExecutor.shutdownNow();
        }
        if (taskExecutor != null) {
            taskExecutor.shutdownNow();
        }

        isRunning.set(false);
        isShuttingDown.set(false);
    }

    /**
     * Добавление фоновой задачи
     */
    public void addBackgroundTask(String functionName, Object parameters, long interval) {
        addBackgroundTask(functionName, parameters, interval,
                TaskPriority.MEDIUM, 0, null, null);
    }

    /**
     * Добавление фоновой задачи с расширенными параметрами
     */
    public void addBackgroundTask(String functionName, Object parameters, long interval,
                                  TaskPriority priority, long timeoutMs,
                                  RetryPolicy retryPolicy, CircuitBreaker circuitBreaker) {

        if (!isRunning.get() || isShuttingDown.get()) {
            Log.w(TAG, "Cannot add task - manager is not running or shutting down");
            return;
        }

        if (!functionRegistry.containsKey(functionName)) {
            throw new IllegalArgumentException(
                    "Function '" + functionName + "' is not registered");
        }

        taskLock.writeLock().lock();
        try {
            // Удаляем существующую задачу с тем же именем
            removeTaskByNameInternal(functionName);

            // Создаём новую задачу
            BackgroundTask task = new BackgroundTask(
                    functionName, parameters, interval, priority,
                    timeoutMs, retryPolicy, circuitBreaker
            );

            // Добавляем в коллекции
            taskMap.put(functionName, task);
            taskQueue.offer(task);

            Log.d(TAG, "Added task: " + functionName +
                    " (priority: " + priority + ", interval: " + interval + "ms)");

        } finally {
            taskLock.writeLock().unlock();
        }
    }

    /**
     * Регистрация функции для выполнения
     */
    public void registerFunction(String functionName, Runnable function) {
        if (function == null) {
            throw new IllegalArgumentException("Function cannot be null");
        }

        functionRegistry.put(functionName, function);
        Log.d(TAG, "Registered function: " + functionName);
    }

    /**
     * Удаление задачи по имени
     */
    public void removeTaskByName(String functionName) {
        taskLock.writeLock().lock();
        try {
            removeTaskByNameInternal(functionName);
        } finally {
            taskLock.writeLock().unlock();
        }
    }

    /**
     * Немедленный запуск задачи
     */
    public void executeNow(String functionName) {
        taskLock.readLock().lock();
        try {
            BackgroundTask task = taskMap.get(functionName);
            if (task != null && !task.isScheduled) {
                task.nextExecutionTime = System.currentTimeMillis();
                Log.d(TAG, "Forced immediate execution of: " + functionName);
            }
        } finally {
            taskLock.readLock().unlock();
        }
    }

    /**
     * Проверка, выполняется ли задача
     */
    public boolean isTaskRunning(String functionName) {
        Future<TaskResult> future = runningTasks.get(functionName);
        return future != null && !future.isDone();
    }

    /**
     * Получение статистики выполнения
     */
    public MetricsCollector.MetricsSnapshot getMetrics() {
        return metrics.getSnapshot();
    }

    /**
     * Получение состояния пула потоков
     */
    public PoolStats getPoolStats() {
        if (taskExecutor == null) {
            return new PoolStats(0, 0, 0, 0, 0);
        }

        return new PoolStats(
                taskExecutor.getActiveCount(),
                taskExecutor.getQueue().size(),
                taskExecutor.getCompletedTaskCount(),
                taskExecutor.getPoolSize(),
                taskExecutor.getMaximumPoolSize()
        );
    }

    public static class PoolStats {
        public final int activeThreads;
        public final int queuedTasks;
        public final long completedTasks;
        public final int currentPoolSize;
        public final int maxPoolSize;

        public PoolStats(int active, int queued, long completed, int current, int max) {
            this.activeThreads = active;
            this.queuedTasks = queued;
            this.completedTasks = completed;
            this.currentPoolSize = current;
            this.maxPoolSize = max;
        }

        public double getQueueLoad() {
            return (double) queuedTasks / DEFAULT_TASK_QUEUE_SIZE;
        }
    }

    // ============ ПРИВАТНЫЕ МЕТОДЫ ============

    /**
     * Основной метод обработки задач
     */
    private void processTasks() {
        if (!isRunning.get() || isShuttingDown.get()) {
            return;
        }

        long currentTime = System.currentTimeMillis();

        taskLock.readLock().lock();
        try {
            // Собираем задачи для выполнения
            List<BackgroundTask> tasksToExecute = new ArrayList<>();

            for (BackgroundTask task : taskQueue) {
                if (tasksToExecute.size() >= 10) { // Ограничиваем batch
                    break;
                }

                if (task.shouldExecuteNow()) {
                    // Проверяем Circuit Breaker
                    if (!task.circuitBreaker.allowExecution()) {
                        Log.d(TAG, "Circuit breaker OPEN for task: " + task.functionName);
                        task.nextExecutionTime = currentTime + 5000; // Откладываем на 5 сек
                        continue;
                    }

                    tasksToExecute.add(task);
                }
            }

            // Запускаем отобранные задачи
            for (BackgroundTask task : tasksToExecute) {
                executeTask(task);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in processTasks", e);
        } finally {
            taskLock.readLock().unlock();
        }
    }

    /**
     * Выполнение конкретной задачи
     */
    private void executeTask(BackgroundTask task) {
        if (task.isScheduled || runningTasks.containsKey(task.functionName)) {
            return;
        }

        task.markScheduled();
        Runnable function = functionRegistry.get(task.functionName);
        if (function == null) {
            Log.e(TAG, "Function not found: " + task.functionName);
            task.markFailed();
            return;
        }

        // Обернем в PriorityRunnable для сохранения приоритета
        PriorityRunnable priorityRunnable = new PriorityRunnable(() -> {
            long startTime = System.currentTimeMillis();
            try {
                function.run();  // Прямое выполнение, без nested submit

                long executionTime = System.currentTimeMillis() - startTime;
                metrics.recordSuccess(task.functionName, executionTime);
                task.circuitBreaker.onSuccess();
                task.markCompleted();
            } catch (Exception e) {
                metrics.recordFailure(task.functionName);
                task.circuitBreaker.onFailure();
                task.markFailed();
            } finally {
                runningTasks.remove(task.functionName);
            }
        }, task.priority);

        Future<?> future = taskExecutor.submit(priorityRunnable);
        runningTasks.put(task.functionName, (Future<TaskResult>) future);
    }

    /**
     * Внутренний метод удаления задачи
     */
    private void removeTaskByNameInternal(String functionName) {
        BackgroundTask task = taskMap.remove(functionName);
        if (task != null) {
            taskQueue.remove(task);

            // Отменяем выполняющуюся задачу
            Future<TaskResult> future = runningTasks.remove(functionName);
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }

            Log.d(TAG, "Removed task: " + functionName);
        }
    }

    /**
     * Удаление всех задач
     */
    public void removeAllTasks() {
        taskLock.writeLock().lock();
        try {
            // Отменяем все выполняющиеся задачи
            for (Future<TaskResult> future : runningTasks.values()) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }

            // Очищаем коллекции
            taskMap.clear();
            taskQueue.clear();
            runningTasks.clear();

            Log.d(TAG, "Removed all tasks");

        } finally {
            taskLock.writeLock().unlock();
        }
    }

    /**
     * Получение списка всех зарегистрированных задач
     */
    public List<TaskInfo> getAllTasks() {
        List<TaskInfo> tasks = new ArrayList<>();

        taskLock.readLock().lock();
        try {
            for (BackgroundTask task : taskQueue) {
                tasks.add(new TaskInfo(
                        task.functionName,
                        task.taskId,
                        task.priority,
                        task.interval,
                        task.nextExecutionTime,
                        task.lastExecutionTime,
                        task.isScheduled,
                        task.executionAttempts
                ));
            }
        } finally {
            taskLock.readLock().unlock();
        }

        return tasks;
    }

    public static class TaskInfo {
        public final String functionName;
        public final String taskId;
        public final TaskPriority priority;
        public final long interval;
        public final long nextExecutionTime;
        public final long lastExecutionTime;
        public final boolean isScheduled;
        public final int executionAttempts;

        public TaskInfo(String functionName, String taskId, TaskPriority priority,
                        long interval, long nextExecutionTime, long lastExecutionTime,
                        boolean isScheduled, int executionAttempts) {
            this.functionName = functionName;
            this.taskId = taskId;
            this.priority = priority;
            this.interval = interval;
            this.nextExecutionTime = nextExecutionTime;
            this.lastExecutionTime = lastExecutionTime;
            this.isScheduled = isScheduled;
            this.executionAttempts = executionAttempts;
        }

        public boolean isPeriodic() {
            return interval > 0;
        }

        public long getTimeUntilNextExecution() {
            return Math.max(0, nextExecutionTime - System.currentTimeMillis());
        }
    }

    /**
     * Проверка активности менеджера
     */
    public boolean isRunning() {
        return isRunning.get() && !isShuttingDown.get();
    }

    /**
     * Сброс Circuit Breaker для функции
     */
    public void resetCircuitBreaker(String functionName) {
        CircuitBreaker breaker = circuitBreakers.get(functionName);
        if (breaker != null) {
            // Нужно добавить метод reset в CircuitBreaker
            // Для простоты создаём новый
            circuitBreakers.put(functionName,
                    new CircuitBreaker(5, 30000, 3));
        }
    }

    /**
     * Получение состояния Circuit Breaker
     */
    public CircuitBreakerState getCircuitBreakerState(String functionName) {
        CircuitBreaker breaker = circuitBreakers.get(functionName);
        return breaker != null ? breaker.getState() : CircuitBreakerState.CLOSED;
    }

    // ============ УТИЛИТНЫЕ МЕТОДЫ ============

    /**
     * Создание задачи с высоким приоритетом
     */
    public static BackgroundTask createHighPriorityTask(String functionName,
                                                        Object parameters, long interval) {
        return new BackgroundTask(functionName, parameters, interval,
                TaskPriority.HIGH, 0, null, null);
    }

    /**
     * Создание задачи с таймаутом
     */
    public static BackgroundTask createTaskWithTimeout(String functionName,
                                                       Object parameters, long interval,
                                                       long timeoutMs) {
        return new BackgroundTask(functionName, parameters, interval,
                TaskPriority.MEDIUM, timeoutMs, null, null);
    }

    /**
     * Создание задачи с кастомной политикой повторных попыток
     */
    public static BackgroundTask createTaskWithRetryPolicy(String functionName,
                                                           Object parameters, long interval,
                                                           RetryPolicy retryPolicy) {
        return new BackgroundTask(functionName, parameters, interval,
                TaskPriority.MEDIUM, 0, retryPolicy, null);
    }

    /**
     * Быстрая проверка доступности менеджера
     */
    public boolean isAvailable() {
        return isRunning() &&
                taskExecutor != null &&
                !taskExecutor.isShutdown() &&
                !taskExecutor.isTerminated();
    }

    /**
     * Получение загрузки системы (0.0 - 1.0)
     */
    public double getSystemLoad() {
        if (!isAvailable()) {
            return 0.0;
        }

        PoolStats stats = getPoolStats();
        double threadLoad = (double) stats.activeThreads / stats.maxPoolSize;
        double queueLoad = (double) stats.queuedTasks / DEFAULT_TASK_QUEUE_SIZE;

        return Math.max(threadLoad, queueLoad);
    }

    /**
     * Пауза менеджера (временная остановка обработки новых задач)
     */
    public void pause() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    /**
     * Возобновление работы менеджера
     */
    public void resume() {
        if (!isRunning.get()) {
            initialize();
        } else if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(
                    new SafeThreadFactory("TaskScheduler", null));

            scheduler.scheduleWithFixedDelay(
                    this::processTasks,
                    0,
                    DEFAULT_CHECK_INTERVAL_MS,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    // ============ ДЕСТРУКТОР (для безопасности) ============

    @Override
    protected void finalize() throws Throwable {
        try {
            if (isRunning.get()) {
                Log.w(TAG, "BackgroundTaskManager finalized without proper shutdown!");
                shutdown();
            }
        } finally {
            super.finalize();
        }
    }
}