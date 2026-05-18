/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.aop;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.config.RAGRateLimitProperties;
import com.nageoffer.ai.ragent.rag.dto.CompletionPayload;
import com.nageoffer.ai.ragent.rag.dto.MessageDelta;
import com.nageoffer.ai.ragent.rag.dto.MetaPayload;
import com.nageoffer.ai.ragent.rag.enums.SSEEventType;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RTopic;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;

import java.util.Objects;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

/**
 * SSE 全局并发限流与排队处理
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatQueueLimiter {

    private static final String REJECT_MESSAGE = "系统繁忙，请稍后再试";
    private static final String RESPONSE_TYPE = "response";
    private static final String SEMAPHORE_NAME = "rag:global:chat";
    private static final String QUEUE_KEY = "rag:global:chat:queue";
    private static final String QUEUE_SEQ_KEY = "rag:global:chat:queue:seq";
    private static final String NOTIFY_TOPIC = "rag:global:chat:queue:notify";
    private static final String CLAIM_LUA_PATH = "lua/queue_claim_atomic.lua";

    private final RedissonClient redissonClient;
    private final RAGRateLimitProperties rateLimitProperties;
    private final ConversationMemoryService memoryService;
    private final ConversationGroupService conversationGroupService;
    private final MemoryProperties memoryProperties;
    @Qualifier("chatEntryExecutor")
    private final Executor chatEntryExecutor;
    private final String claimLua = loadLuaScript();
    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(
            1,
            r -> {
                Thread thread = new Thread(r);
                thread.setName("chat_queue_scheduler");
                thread.setDaemon(true);
                return thread;
            }
    );
    private volatile int notifyListenerId = -1;
    private volatile PollNotifier pollNotifier;

    @PostConstruct
    public void subscribeQueueNotify() {
        pollNotifier = new PollNotifier(this::availablePermits);
        pollNotifier.startCleanup();
        RTopic topic = redissonClient.getTopic(NOTIFY_TOPIC);
        notifyListenerId = topic.addListener(String.class, (channel, msg) -> {
            if (pollNotifier != null) {
                pollNotifier.fire();
            }
        });
    }

    public void enqueue(String question, String conversationId, SseEmitter emitter, Runnable onAcquire) {
        // 限流开关关闭时，直接执行
        if (!Boolean.TRUE.equals(rateLimitProperties.getGlobalEnabled())) {
            chatEntryExecutor.execute(onAcquire);
            return;
        }

        // ② 生成请求ID（雪花算法，全局唯一）
        String userId = resolveUserId();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<String> permitRef = new AtomicReference<>();
        String requestId = IdUtil.getSnowflakeNextIdStr();// 雪花ID作为请求唯一标识
        RScoredSortedSet<String> queue = redissonClient.getScoredSortedSet(QUEUE_KEY, StringCodec.INSTANCE);
        long seq = nextQueueSeq();  // ③ 单调递增 seq 作 ZSET score
        queue.add(seq, requestId);  // ④ 入队
        // ⑤ 绑定 SSE 取消回调（用户断连时清理队列+释放许可）
        Runnable releaseOnce = () -> {
            cancelled.set(true);// 告诉后台轮询"别管我了"
            queue.remove(requestId);// 从队列里把自己删掉（seq=5 没了）
            String permitId = permitRef.getAndSet(null);// 把令牌还回去
            if (permitId != null) {
                redissonClient.getPermitExpirableSemaphore(SEMAPHORE_NAME)
                        .release(permitId);
                publishQueueNotify(); // 喊一声"有空位了"
            }
        };

        // 当队列中有任务执行结束、超时、报错：触发releaseOnce
        emitter.onCompletion(releaseOnce);
        emitter.onTimeout(releaseOnce);
        emitter.onError(e -> releaseOnce.run());

        // ⑥ 立刻尝试抢占
        if (tryAcquireIfReady(queue, requestId, permitRef, cancelled, onAcquire)) {
            return;
        }

        scheduleQueuePoll(queue, requestId, permitRef, cancelled, question, conversationId, userId, emitter, onAcquire);
    }

    /**
     * 轮询等待入口 —— 当 tryAcquireIfReady 抢不到令牌时，调用这个方法。
     *
     * 工作机制：启动一个定时任务，每隔 intervalMs 毫秒检查一次"是不是轮到我了"。
     * 同时把自己注册到 PollNotifier，收到通知时立即检查（不用等定时器）。
     *
     * 两种退出方式：
     *   1. 超时退出  → 等太久没人叫我，直接放弃（发送"系统繁忙"）
     *   2. 抢到退出  → tryAcquireIfReady 返回 true，停止轮询，开始执行
     */
    private void scheduleQueuePoll(RScoredSortedSet<String> queue,
                                   String requestId,
                                   AtomicReference<String> permitRef,
                                   AtomicBoolean cancelled,
                                   String question,
                                   String conversationId,
                                   String userId,
                                   SseEmitter emitter,
                                   Runnable onAcquire) {
        // 超时截止时间 = 现在 + 最大等待秒数
        long deadline = System.currentTimeMillis()
                + TimeUnit.SECONDS.toMillis(rateLimitProperties.getGlobalMaxWaitSeconds());
        // 轮询间隔，至少 50ms，避免对 Redis 压力太大
        int intervalMs = Math.max(50,
                Objects.requireNonNullElse(rateLimitProperties.getGlobalPollIntervalMs(), 200));
        PollNotifier notifier = pollNotifier;
        // 用数组包装 ScheduledFuture，支持在 poller 内部取消自己
        ScheduledFuture<?>[] futureRef = new ScheduledFuture<?>[1];

        /**
         * 轮询任务 —— 每次定时器触发时执行一次。
         * 这里面的逻辑和 tryAcquireIfReady 类似：检查 → 抢令牌 → 成功或失败。
         */
        Runnable poller = () -> {
            // ① 我已经被取消了（比如 SSE 断连了），停止轮询
            if (cancelled.get()) {
                if (notifier != null) {
                    notifier.unregister(requestId);
                }
                cancelFuture(futureRef[0]);
                return;
            }

            // ② 超时了吗？等太久直接放弃，告诉用户"系统繁忙"
            if (System.currentTimeMillis() > deadline) {
                queue.remove(requestId);              // 从队列删掉自己
                publishQueueNotify();                 // 通知其他人重新检查
                if (notifier != null) {
                    notifier.unregister(requestId);
                }
                cancelFuture(futureRef[0]);
                if (!cancelled.get()) {
                    // 没被取消过才发拒绝消息（避免 double 发）
                    RejectedContext rejectedContext = recordRejectedConversation(question, conversationId, userId);
                    sendRejectEvents(emitter, rejectedContext);
                }
                return;
            }

            // ③ 再次尝试抢令牌 —— 核心逻辑，和 tryAcquireIfReady 一样
            if (tryAcquireIfReady(queue, requestId, permitRef, cancelled, onAcquire)) {
                // 抢到了！从 PollNotifier 注销自己（不再需要通知唤醒）
                if (notifier != null) {
                    notifier.unregister(requestId);
                }
                cancelFuture(futureRef[0]);
            }
            // 如果没抢到，什么都不做，下一次定时器触发再来
        };

        // 启动定时轮询：每 intervalMs ms 执行一次 poller
        futureRef[0] = scheduler.scheduleAtFixedRate(poller, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        // 注册到 PollNotifier，这样收到通知时可以立即触发 poller（不用等定时器）
        if (notifier != null) {
            notifier.register(requestId, poller);
        }
    }

    /**
     * 尝试抢占令牌并执行任务。
     *
     * 完整流程（全部为非阻塞检查）：
     *   ① cancelled？→ 放弃
     *   ② 有空闲令牌？→ 否则放弃
     *   ③ 我是队首？→ 否则放弃（用 Lua 脚本原子判断）
     *   ④ 抢到 permit？→ 否则放弃（其他请求先下手为强了）
     *   ⑤ 再次确认 cancelled？（抢到之后到执行前，用户可能已经断连）
     *   ⑥ 提交线程池执行
     *
     * @return true 表示成功抢到并开始执行；false 表示放弃（进入轮询等待）
     */
    private boolean tryAcquireIfReady(RScoredSortedSet<String> queue,
                                      String requestId,
                                      AtomicReference<String> permitRef,
                                      AtomicBoolean cancelled,
                                      Runnable onAcquire) {
        // ① 已经被取消了（比如 releaseOnce 已触发），直接放弃抢令牌
        if (cancelled.get()) {
            return false;
        }

        // ② 有没有空闲的坑位？没有任何坑位就不尝试了
        int availablePermits = availablePermits();
        if (availablePermits <= 0) {
            return false;
        }

        // ③ 我是队首吗？Lua 脚本原子判断（ZRANGE 0 0）
        //    claimIfReady 会先查 ZSET 第一名是不是自己，只有是才认为"认领成功"
        ClaimResult claimResult = claimIfReady(queue, requestId, availablePermits);
        if (!claimResult.claimed) {
            return false;
        }

        // ④ 真正去抢 permit——这里已经不是原子操作了，其他请求可能同时抢同一批坑位
        //    所以 tryAcquire 可能会失败（返回 null），失败了就放弃本次尝试
        String permitId = tryAcquirePermit();
        if (permitId == null) {
            // 抢失败了，把自己重新放回队尾（获取新的 seq），然后通知其他人重新检查
            long newSeq = nextQueueSeq();
            queue.add(newSeq, requestId);
            publishQueueNotify();
            return false;
        }

        // 抢到了 permit，把 permitId 存到共享引用中，供 releaseOnce 使用
        permitRef.set(permitId);

        // ⑤ double-check：拿到 permit 之后、提交线程池之前，用户可能已经断连了
        //    此时 releaseOnce 已经把 cancelled 设为 true，必须放弃执行
        if (cancelled.get()) {
            releasePermit(permitId, permitRef);
            return false;
        }

        // 通知其他等待中的请求：有人执行了，你们重新检查自己的位置
        publishQueueNotify();

        // ⑥ 终于！提交到线程池正式执行
        try {
            chatEntryExecutor.execute(() -> runOnAcquire(onAcquire));
        } catch (RuntimeException ex) {
            // 线程池拒绝（比如队列满了），此时必须把 permit 还回去，否则令牌永久丢失
            releasePermit(permitId, permitRef);
            if (!cancelled.get()) {
                // 没被取消过，把自己重新入队排到队尾，等下一次机会
                long newSeq = nextQueueSeq();
                queue.add(newSeq, requestId);
                publishQueueNotify();
            }
            log.warn("排队后提交任务失败，已释放 permit 并重新入队", ex);
            return false;
        }
        return true;
    }

    /**
     * 尝试从信号量中抢占一个 permit。
     * Permit 带有过期时间（globalLeaseSeconds），即使任务崩溃也能自动释放。
     *
     * @return permit 的唯一 ID（用于之后 release）；null 表示抢不到
     */
    private String tryAcquirePermit() {
        RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(
                SEMAPHORE_NAME
        );
        // 保证信号量的总坑位数已设置（幂等操作）
        semaphore.trySetPermits(rateLimitProperties.getGlobalMaxConcurrent());
        try {
            // tryAcquire(0, leaseTime) = 立刻尝试，不等待
            return semaphore.tryAcquire(0, rateLimitProperties.getGlobalLeaseSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 查询当前信号量还剩多少个空闲坑位。
     */
    private int availablePermits() {
        RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(
                SEMAPHORE_NAME
        );
        semaphore.trySetPermits(rateLimitProperties.getGlobalMaxConcurrent());
        return semaphore.availablePermits();
    }

    /**
     * 执行 Lua 脚本，原子判断"我是不是队列中的第一名"。
     *
     * Lua 脚本逻辑：
     *   1. ZRANGE queue 0 0  —— 取队列第一个元素
     *   2. 如果队列为空，返回 {0, 0}
     *   3. 如果第一个元素就是我，返回 {1, score}
     *   4. 否则返回 {0, 0}
     *
     * 注意：这里只判断"我是不是队首"，不修改队列。修改队列（移除自己）在 releaseOnce 中做。
     *
     * @param queue             队列（用于获取 key 名传给 Lua）
     * @param requestId         当前请求的雪花 ID
     * @param availablePermits  当前可用坑位数（Lua 脚本用这个参数是为了和 claimLua 保持一致的函数签名，实际没用上）
     * @return claimed=true 表示认领成功（我是队首）；claimed=false 表示不是队首或队列为空
     */
    private ClaimResult claimIfReady(RScoredSortedSet<String> queue, String requestId, int availablePermits) {
        RScript script = redissonClient.getScript(StringCodec.INSTANCE);
        List<Object> result = script.eval(
                RScript.Mode.READ_WRITE,
                claimLua,
                RScript.ReturnType.LIST,
                List.of(queue.getName()),
                requestId,
                String.valueOf(availablePermits)
        );
        if (result == null || result.isEmpty()) {
            return ClaimResult.notClaimed();
        }
        Object ok = result.get(0);
        long okValue = parseLong(ok);
        if (okValue != 1L || result.size() < 2) {
            return ClaimResult.notClaimed();
        }
        Object scoreObj = result.get(1);
        double score = scoreObj == null ? System.currentTimeMillis() : Double.parseDouble(scoreObj.toString());
        return new ClaimResult(true, score);
    }

    private long parseLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private long nextQueueSeq() {
        RAtomicLong seq = redissonClient.getAtomicLong(QUEUE_SEQ_KEY);
        return seq.incrementAndGet();
    }

    private void cancelFuture(ScheduledFuture<?> future) {
        if (future != null && !future.isCancelled()) {
            future.cancel(false);
        }
    }

    private void publishQueueNotify() {
        redissonClient.getTopic(NOTIFY_TOPIC).publish("permit_released");
    }

    private RejectedContext recordRejectedConversation(String question, String conversationId, String userId) {
        if (StrUtil.isBlank(question)) {
            return null;
        }

        if (StrUtil.isBlank(userId)) {
            try {
                userId = StpUtil.getLoginIdAsString();
            } catch (Exception ignored) {
                return null;
            }
        }

        String actualConversationId = StrUtil.isBlank(conversationId)
                ? IdUtil.getSnowflakeNextIdStr()
                : conversationId;
        boolean isNewConversation = conversationGroupService.findConversation(actualConversationId, userId) == null;

        memoryService.append(actualConversationId, userId, ChatMessage.user(question));
        String messageId = memoryService.append(actualConversationId, userId, ChatMessage.assistant(REJECT_MESSAGE));

        String title = isNewConversation ? resolveTitle(actualConversationId, userId) : "";
        if (isNewConversation && StrUtil.isBlank(title)) {
            title = buildFallbackTitle(question);
        }
        String taskId = IdUtil.getSnowflakeNextIdStr();
        return new RejectedContext(actualConversationId, taskId, messageId, title);
    }

    private String resolveTitle(String conversationId, String userId) {
        var conversation = conversationGroupService.findConversation(conversationId, userId);
        if (conversation == null) {
            return "";
        }
        return conversation.getTitle();
    }

    private String buildFallbackTitle(String question) {
        if (StrUtil.isBlank(question)) {
            return "";
        }
        int maxLen = memoryProperties.getTitleMaxLength() != null ? memoryProperties.getTitleMaxLength() : 30;
        String cleaned = question.trim();
        if (cleaned.length() <= maxLen) {
            return cleaned;
        }
        return cleaned.substring(0, maxLen);
    }

    private void sendRejectEvents(SseEmitter emitter, RejectedContext rejectedContext) {
        SseEmitterSender sender = new SseEmitterSender(emitter);
        if (rejectedContext != null) {
            sender.sendEvent(SSEEventType.META.value(), new MetaPayload(rejectedContext.conversationId, rejectedContext.taskId));
            sender.sendEvent(SSEEventType.REJECT.value(), new MessageDelta(RESPONSE_TYPE, REJECT_MESSAGE));
            String title = rejectedContext.title;
            String messageId = String.valueOf(String.valueOf(rejectedContext.messageId));
            sender.sendEvent(SSEEventType.FINISH.value(), new CompletionPayload(messageId, title));
        }
        sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
        sender.complete();
    }

    private record RejectedContext(String conversationId, String taskId, String messageId, String title) {
    }

    private record ClaimResult(boolean claimed, double score) {
        static ClaimResult notClaimed() {
            return new ClaimResult(false, 0D);
        }
    }

    private void releasePermit(String permitId, AtomicReference<String> permitRef) {
        if (permitRef.compareAndSet(permitId, null)) {
            redissonClient.getPermitExpirableSemaphore(SEMAPHORE_NAME)
                    .release(permitId);
            publishQueueNotify();
        }
    }

    private String loadLuaScript() {
        try {
            ClassPathResource resource = new ClassPathResource(CLAIM_LUA_PATH);
            return StreamUtils.copyToString(resource.getInputStream(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load lua script: " + CLAIM_LUA_PATH, ex);
        }
    }

    private void runOnAcquire(Runnable onAcquire) {
        try {
            onAcquire.run();
        } catch (Exception ex) {
            log.warn("执行排队后入口失败", ex);
        }
    }

    private String resolveUserId() {
        String userId = UserContext.getUserId();
        if (StrUtil.isNotBlank(userId)) {
            return userId;
        }
        try {
            return StpUtil.getLoginIdAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    @PreDestroy
    public void shutdown() {
        if (notifyListenerId != -1) {
            redissonClient.getTopic(NOTIFY_TOPIC).removeListener(notifyListenerId);
        }
        scheduler.shutdown();
        awaitSchedulerShutdown();
        if (pollNotifier != null) {
            pollNotifier.shutdown();
        }
    }

    private void awaitSchedulerShutdown() {
        try {
            if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException ex) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 轮询通知器 —— 负责在有空闲坑位时立即唤醒等待中的轮询任务。
     *
     * 工作原理：
     * - 当 permit 被释放时（releaseOnce 触发），publishQueueNotify() 发布到 Redis Topic
     * - 所有 JVM 实例都会收到这条消息，PollNotifier.onMessage 被调用
     * - onMessage 调用 fire()，把当前 JVM 中所有等待中的 poller 全部执行一遍
     *
     * 这样做的意义：不用等定时器（200ms 一次），收到通知后立刻去抢令牌，延迟从 200ms 降到 ~0ms。
     *
     * 为什么需要 firing 锁？
     * - fire() 可能被多个线程同时调用（比如多 JVM 实例 + 本机重复通知）
     * - 用 AtomicBoolean compareAndSet 保证同一时刻只有一个线程在执行 do { ... } 循环
     * - pendingNotifications 保证在执行期间新来的通知不会被漏掉
     *
     * 为什么需要 cleanupExecutor？
     * - poller 执行完后不会主动注销（只有抢到、超时、取消时才注销）
     * - 如果客户端断连但忘记 unregister（极端情况），poller 会永远留在内存
     * - cleanupExecutor 每 5 分钟清理一次注册时间超过 5 分钟的僵尸 poller
     */
    private static final class PollNotifier {
        private final IntSupplier permitSupplier;
        private final ScheduledExecutorService notifyExecutor = new ScheduledThreadPoolExecutor(
                1,
                r -> {
                    Thread thread = new Thread(r);
                    thread.setName("chat_queue_notify");
                    thread.setDaemon(true);
                    return thread;
                }
        );
        private final java.util.concurrent.ConcurrentHashMap<String, PollerEntry> pollers = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicBoolean firing = new java.util.concurrent.atomic.AtomicBoolean(false);
        private final ScheduledExecutorService cleanupExecutor = new ScheduledThreadPoolExecutor(
                1,
                r -> {
                    Thread thread = new Thread(r);
                    thread.setName("chat_queue_cleanup");
                    thread.setDaemon(true);
                    return thread;
                }
        );

        PollNotifier(IntSupplier permitSupplier) {
            this.permitSupplier = permitSupplier;
        }

        private record PollerEntry(Runnable poller, long registerTime) {
        }

        void register(String requestId, Runnable poller) {
            pollers.put(requestId, new PollerEntry(poller, System.currentTimeMillis()));
        }

        void unregister(String requestId) {
            pollers.remove(requestId);
        }

        private final java.util.concurrent.atomic.AtomicInteger pendingNotifications = new java.util.concurrent.atomic.AtomicInteger(0);

        void fire() {
            pendingNotifications.incrementAndGet();
            if (!firing.compareAndSet(false, true)) {
                return;
            }
            notifyExecutor.execute(() -> {
                do {
                    pendingNotifications.set(0);
                    try {
                        if (permitSupplier.getAsInt() <= 0) {
                            continue;
                        }
                        for (PollerEntry entry : pollers.values()) {
                            entry.poller().run();
                        }
                    } finally {
                        firing.set(false);
                    }
                } while (pendingNotifications.get() > 0 && firing.compareAndSet(false, true));
            });
        }

        void shutdown() {
            cleanupExecutor.shutdown();
            notifyExecutor.shutdown();
            awaitExecutorShutdown(cleanupExecutor);
            awaitExecutorShutdown(notifyExecutor);
            pollers.clear();
        }

        private void awaitExecutorShutdown(ScheduledExecutorService executor) {
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException ex) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        private void startCleanup() {
            cleanupExecutor.scheduleAtFixedRate(() -> {
                long now = System.currentTimeMillis();
                pollers.entrySet().removeIf(entry ->
                        now - entry.getValue().registerTime() > TimeUnit.MINUTES.toMillis(5)
                );
            }, 1, 1, TimeUnit.MINUTES);
        }
    }
}
