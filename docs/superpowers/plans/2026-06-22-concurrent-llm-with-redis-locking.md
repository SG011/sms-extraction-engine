# Concurrent LLM with Redis Locking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Process batch messages in parallel using virtual threads, with Redis-based per-sender locking and pub/sub to prevent redundant concurrent LLM calls for the same sender.

**Architecture:** Each batch message is dispatched to a virtual thread via `CompletableFuture`. When a message needs an LLM call, it acquires a Redisson `RLock` per `senderId`, sets a Redis state (`LEARNING_IN_PROGRESS`), and publishes on an `RTopic` when done. Concurrent messages for the same sender subscribe to that topic and wait, then retry their full extraction + template match once notified. A global `Semaphore(20)` caps LLM concurrency across all senders.

**Tech Stack:** Java 21, Spring Boot 3.2, Redisson 3.x (`redisson-spring-boot-starter`), Redis, Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`), `java.util.concurrent.Semaphore`

## Global Constraints

- Java compiler source/target/release must be upgraded from 17 → 21
- `spring.threads.virtual.enabled=true` must be set in `application.properties`
- All new Spring beans use constructor injection only — no `@Autowired` on fields
- Max LLM concurrency configurable via `llm.max-concurrent-calls=20` in `application.properties`
- Redis TTL for `LEARNING_IN_PROGRESS` state = 120 seconds (2 minutes)
- Redis state keys: `sms:learning:state:{senderId}`
- Redis topic names: `sms:learning:done:{senderId}`
- State values stored as strings: `LEARNING_IN_PROGRESS`, `LEARNED`, `LEARNING_FAILED`

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `pom.xml` | Modify | Upgrade Java 17→21, add `redisson-spring-boot-starter` |
| `application.properties` | Modify | Redis config, virtual threads, LLM concurrency limit |
| `config/RedissonConfig.java` | Create | Redisson client bean |
| `service/SenderLearningStateService.java` | Create | Interface: acquire lock, set state, publish, subscribe |
| `service/impl/SenderLearningStateServiceImpl.java` | Create | Redisson implementation of above |
| `service/SmsExtractionService.java` | No change | Existing interface |
| `service/impl/SmsExtractionServiceImpl.java` | Modify | Inject `SenderLearningStateService` + `Semaphore`, wrap LLM call in lock/semaphore |
| `controller/SmsController.java` | Modify | Parallel batch via `CompletableFuture` + virtual thread executor |

---

### Task 1: Upgrade Java 21 + add Redisson dependency

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.properties`

**Interfaces:**
- Produces: `RedissonClient` bean available for injection in later tasks

- [ ] **Step 1: Update pom.xml — Java 21 compiler + Redisson dependency**

In `pom.xml`, change the `maven-compiler-plugin` configuration:
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <source>21</source>
        <target>21</target>
        <release>21</release>
    </configuration>
</plugin>
```

Add dependency inside `<dependencies>`:
```xml
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <version>3.27.2</version>
</dependency>
```

- [ ] **Step 2: Add Redis + virtual thread config to `application.properties`**

```properties
# Redis
spring.data.redis.host=localhost
spring.data.redis.port=6379

# Virtual threads
spring.threads.virtual.enabled=true

# LLM concurrency
llm.max-concurrent-calls=20

# Redisson (single-node for local dev; override in prod)
spring.redis.redisson.config=classpath:redisson.yaml
```

- [ ] **Step 3: Create `src/main/resources/redisson.yaml`**

```yaml
singleServerConfig:
  address: "redis://localhost:6379"
  connectionMinimumIdleSize: 2
  connectionPoolSize: 10
```

- [ ] **Step 4: Build to confirm compilation**

```bash
mvn compile -q
```
Expected: `BUILD SUCCESS` with no errors.

- [ ] **Step 5: Run existing tests**

```bash
mvn test -q 2>&1 | grep -E "Tests run:|BUILD"
```
Expected: `BUILD SUCCESS`, same pass/fail counts as before.

---

### Task 2: Create `RedissonConfig` and `SenderLearningStateService`

**Files:**
- Create: `src/main/java/com/sms/extraction/config/RedissonConfig.java`
- Create: `src/main/java/com/sms/extraction/service/SenderLearningStateService.java`
- Create: `src/main/java/com/sms/extraction/service/impl/SenderLearningStateServiceImpl.java`
- Test: `src/test/java/com/sms/extraction/service/SenderLearningStateServiceTest.java`

**Interfaces:**
- Consumes: `RedissonClient` (auto-configured by `redisson-spring-boot-starter`)
- Produces:
  - `SenderLearningStateService.acquireLock(String senderId)` → `void` (blocks until lock acquired)
  - `SenderLearningStateService.releaseLock(String senderId)` → `void`
  - `SenderLearningStateService.setState(String senderId, LearningState state)` → `void`
  - `SenderLearningStateService.getState(String senderId)` → `LearningState` (null if absent)
  - `SenderLearningStateService.waitForCompletion(String senderId)` → `void` (blocks until topic notified)
  - `SenderLearningStateService.publishCompletion(String senderId)` → `void`
  - `LearningState` enum: `LEARNING_IN_PROGRESS`, `LEARNED`, `LEARNING_FAILED`

- [ ] **Step 1: Write failing test**

```java
// src/test/java/com/sms/extraction/service/SenderLearningStateServiceTest.java
package com.sms.extraction.service;

import com.sms.extraction.service.impl.SenderLearningStateServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SenderLearningStateServiceTest {

    private RedissonClient redissonClient;
    private SenderLearningStateService service;

    @BeforeEach
    void setUp() {
        redissonClient = mock(RedissonClient.class);
        service = new SenderLearningStateServiceImpl(redissonClient, 120);
    }

    @Test
    @DisplayName("setState LEARNING_IN_PROGRESS writes correct key with TTL")
    void setStateLearningInProgress() {
        RBucket<String> bucket = mock(RBucket.class);
        when(redissonClient.getBucket("sms:learning:state:HDFC-BANK")).thenReturn(bucket);

        service.setState("HDFC-BANK", SenderLearningStateService.LearningState.LEARNING_IN_PROGRESS);

        verify(bucket).set("LEARNING_IN_PROGRESS", 120, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("setState LEARNED writes value without TTL")
    void setStateLearned() {
        RBucket<String> bucket = mock(RBucket.class);
        when(redissonClient.getBucket("sms:learning:state:HDFC-BANK")).thenReturn(bucket);

        service.setState("HDFC-BANK", SenderLearningStateService.LearningState.LEARNED);

        verify(bucket).set("LEARNED");
    }

    @Test
    @DisplayName("getState returns null when key absent")
    void getStateAbsent() {
        RBucket<String> bucket = mock(RBucket.class);
        when(redissonClient.getBucket("sms:learning:state:HDFC-BANK")).thenReturn(bucket);
        when(bucket.get()).thenReturn(null);

        assertThat(service.getState("HDFC-BANK")).isNull();
    }

    @Test
    @DisplayName("getState returns LEARNING_IN_PROGRESS when set")
    void getStateLearningInProgress() {
        RBucket<String> bucket = mock(RBucket.class);
        when(redissonClient.getBucket("sms:learning:state:HDFC-BANK")).thenReturn(bucket);
        when(bucket.get()).thenReturn("LEARNING_IN_PROGRESS");

        assertThat(service.getState("HDFC-BANK"))
                .isEqualTo(SenderLearningStateService.LearningState.LEARNING_IN_PROGRESS);
    }

    @Test
    @DisplayName("publishCompletion sends message on topic")
    void publishCompletion() {
        RTopic topic = mock(RTopic.class);
        when(redissonClient.getTopic("sms:learning:done:HDFC-BANK")).thenReturn(topic);

        service.publishCompletion("HDFC-BANK");

        verify(topic).publish("done");
    }

    @Test
    @DisplayName("acquireLock calls lock on RLock for senderId")
    void acquireLock() throws InterruptedException {
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock("sms:learning:lock:HDFC-BANK")).thenReturn(lock);

        service.acquireLock("HDFC-BANK");

        verify(lock).lock();
    }

    @Test
    @DisplayName("releaseLock unlocks RLock for senderId")
    void releaseLock() {
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock("sms:learning:lock:HDFC-BANK")).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        service.releaseLock("HDFC-BANK");

        verify(lock).unlock();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -pl . -Dtest=SenderLearningStateServiceTest -q 2>&1 | tail -5
```
Expected: `BUILD FAILURE` — class not found.

- [ ] **Step 3: Create the `LearningState` enum + interface**

```java
// src/main/java/com/sms/extraction/service/SenderLearningStateService.java
package com.sms.extraction.service;

public interface SenderLearningStateService {

    enum LearningState { LEARNING_IN_PROGRESS, LEARNED, LEARNING_FAILED }

    void acquireLock(String senderId);
    void releaseLock(String senderId);
    void setState(String senderId, LearningState state);
    LearningState getState(String senderId);
    void waitForCompletion(String senderId);
    void publishCompletion(String senderId);
}
```

- [ ] **Step 4: Create the implementation**

```java
// src/main/java/com/sms/extraction/service/impl/SenderLearningStateServiceImpl.java
package com.sms.extraction.service.impl;

import com.sms.extraction.service.SenderLearningStateService;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class SenderLearningStateServiceImpl implements SenderLearningStateService {

    private static final Logger log = LoggerFactory.getLogger(SenderLearningStateServiceImpl.class);

    private static final String STATE_KEY_PREFIX = "sms:learning:state:";
    private static final String LOCK_KEY_PREFIX  = "sms:learning:lock:";
    private static final String TOPIC_KEY_PREFIX = "sms:learning:done:";

    private final RedissonClient redissonClient;
    private final int learningInProgressTtlSeconds;

    public SenderLearningStateServiceImpl(RedissonClient redissonClient,
                                           @Value("${redis.learning.ttl-seconds:120}") int learningInProgressTtlSeconds) {
        this.redissonClient = redissonClient;
        this.learningInProgressTtlSeconds = learningInProgressTtlSeconds;
    }

    @Override
    public void acquireLock(String senderId) {
        redissonClient.getLock(LOCK_KEY_PREFIX + senderId).lock();
    }

    @Override
    public void releaseLock(String senderId) {
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + senderId);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    @Override
    public void setState(String senderId, LearningState state) {
        RBucket<String> bucket = redissonClient.getBucket(STATE_KEY_PREFIX + senderId);
        if (state == LearningState.LEARNING_IN_PROGRESS) {
            bucket.set(state.name(), learningInProgressTtlSeconds, TimeUnit.SECONDS);
        } else {
            bucket.set(state.name());
        }
        log.debug("Sender {} state → {}", senderId, state);
    }

    @Override
    public LearningState getState(String senderId) {
        String value = redissonClient.<String>getBucket(STATE_KEY_PREFIX + senderId).get();
        if (value == null) return null;
        return LearningState.valueOf(value);
    }

    @Override
    public void waitForCompletion(String senderId) {
        CountDownLatch latch = new CountDownLatch(1);
        RTopic topic = redissonClient.getTopic(TOPIC_KEY_PREFIX + senderId);
        int listenerId = topic.addListener(String.class, (channel, msg) -> latch.countDown());
        try {
            boolean signalled = latch.await(learningInProgressTtlSeconds, TimeUnit.SECONDS);
            if (!signalled) {
                log.warn("Timed out waiting for sender {} to finish learning", senderId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            topic.removeListener(listenerId);
        }
    }

    @Override
    public void publishCompletion(String senderId) {
        redissonClient.getTopic(TOPIC_KEY_PREFIX + senderId).publish("done");
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
mvn test -pl . -Dtest=SenderLearningStateServiceTest -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`, 7 tests pass.

- [ ] **Step 6: Run full test suite**

```bash
mvn test -q 2>&1 | grep -E "Tests run:|BUILD" | tail -3
```
Expected: `BUILD SUCCESS`, no new failures.

---

### Task 3: Integrate locking into `SmsExtractionServiceImpl`

**Files:**
- Modify: `src/main/java/com/sms/extraction/service/impl/SmsExtractionServiceImpl.java`

**Interfaces:**
- Consumes:
  - `SenderLearningStateService.acquireLock(String senderId)`
  - `SenderLearningStateService.releaseLock(String senderId)`
  - `SenderLearningStateService.setState(String senderId, LearningState)`
  - `SenderLearningStateService.getState(String senderId)` → `LearningState`
  - `SenderLearningStateService.waitForCompletion(String senderId)`
  - `SenderLearningStateService.publishCompletion(String senderId)`
  - `Semaphore` injected via constructor with `@Value("${llm.max-concurrent-calls:20}")`

**New LLM path logic** (replaces the existing `else` block in `process()`):

```
if message needs LLM:
    check Redis state for senderId
    if LEARNING_IN_PROGRESS:
        waitForCompletion(senderId)       // blocks virtual thread
        retry: reload entities, re-extract, re-match
        if HIT → return
        if still MISS → fall through to LLM path below
    // No state, LEARNED, or LEARNING_FAILED — proceed with LLM
    acquireLock(senderId)
    setState(senderId, LEARNING_IN_PROGRESS)
    try:
        semaphore.acquire()
        call LLM
        semaphore.release()
        save template + entities
        setState(senderId, confidence >= threshold ? LEARNED : LEARNING_FAILED)
    finally:
        publishCompletion(senderId)
        releaseLock(senderId)
```

- [ ] **Step 1: Add `SenderLearningStateService` and `Semaphore` to constructor**

In `SmsExtractionServiceImpl.java`, add two new fields and update the constructor:

```java
private final SenderLearningStateService learningStateService;
private final Semaphore llmSemaphore;
```

Add to constructor parameters:
```java
SenderLearningStateService learningStateService,
@Value("${llm.max-concurrent-calls:20}") int maxConcurrentLlmCalls
```

Add to constructor body:
```java
this.learningStateService = learningStateService;
this.llmSemaphore = new Semaphore(maxConcurrentLlmCalls);
```

Add import: `java.util.concurrent.Semaphore`

- [ ] **Step 2: Add the retry-after-wait helper method**

Add private method to `SmsExtractionServiceImpl`:

```java
private ExtractionResult retryAfterLearningComplete(String senderId, String rawSms,
                                                      String normalizedSms, long startMs) {
    List<GlobalEntity> refreshedEntities = entityRepository.findBySenderId(senderId);
    log.info("  [RETRY] Reloaded {} entities for senderId={} after wait", refreshedEntities.size(), senderId);

    List<CandidateTemplate> retryCandidates = entityExtractionService.extract(normalizedSms, refreshedEntities);
    if (retryCandidates.isEmpty()) return null;

    TemplateMatchOutcome retryOutcome = templateMatchingService.match(normalizedSms, retryCandidates, senderId);
    if (!retryOutcome.isMatched()) return null;

    TemplateMatchResult match = retryOutcome.getMatchResult().get();
    log.info("  [RETRY] TEMPLATE HIT after wait templateId={}", match.getTemplate().getTemplateId());
    metrics.incrementTemplateHits();
    Map<String, String> extractedEntities = buildExtractedEntitiesWithSemanticTypes(
            match.getWinningCandidate(), match.getTemplate().getEntitySnapshots(), normalizedSms);
    return buildResult(senderId, rawSms, normalizedSms,
            match.getTemplate().getTemplateId(), match.getTemplate().getCategory(),
            match.getTemplate().getSubcategory(), match.getTemplate().getIntent(),
            match.getTemplate().getConfidenceScore(), extractedEntities);
}
```

- [ ] **Step 3: Replace the LLM call section with locking logic**

In the `process()` method, replace the section starting at `LlmReason llmReason = matchOutcome.getFailReason();` with:

```java
LlmReason llmReason = matchOutcome.getFailReason();
log.info("  [5] LLM PATH reason={} senderId={}", llmReason, senderId);

// Check if another thread is already learning for this sender
SenderLearningStateService.LearningState currentState = learningStateService.getState(senderId);
if (currentState == SenderLearningStateService.LearningState.LEARNING_IN_PROGRESS) {
    log.info("  [5] WAITING — senderId={} already learning, subscribing to completion topic", senderId);
    learningStateService.waitForCompletion(senderId);
    ExtractionResult retryResult = retryAfterLearningComplete(senderId, rawSms, normalizedSms, startMs);
    if (retryResult != null) {
        saveAndIndex(retryResult);
        return retryResult;
    }
    log.info("  [5] RETRY MISS — still no template match after wait, proceeding with own LLM call");
}

metrics.incrementLlmCalls();
metrics.incrementLlmReason(llmReason);
path = "LLM";

learningStateService.acquireLock(senderId);
learningStateService.setState(senderId, SenderLearningStateService.LearningState.LEARNING_IN_PROGRESS);

long llmStart = System.currentTimeMillis();
LlmResponse llmResponse;
try {
    llmSemaphore.acquire();
    try {
        llmResponse = llmClient.call(normalizedSms, globalEntities);
    } finally {
        llmSemaphore.release();
    }
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    learningStateService.setState(senderId, SenderLearningStateService.LearningState.LEARNING_FAILED);
    learningStateService.publishCompletion(senderId);
    learningStateService.releaseLock(senderId);
    throw new RuntimeException("LLM call interrupted", e);
} catch (Exception e) {
    metrics.incrementLlmCallsFailed();
    log.error("  [5] LLM FAILED senderId={}", senderId, e);
    learningStateService.setState(senderId, SenderLearningStateService.LearningState.LEARNING_FAILED);
    learningStateService.publishCompletion(senderId);
    learningStateService.releaseLock(senderId);
    throw e;
}
llmLatencyMs = System.currentTimeMillis() - llmStart;
metrics.recordLlmLatency(llmLatencyMs);
metrics.addTokenUsage(llmResponse.getInputTokens(), llmResponse.getOutputTokens(),
        llmResponse.getCachedTokens(), llmResponse.getReasoningTokens());
log.info("  [5] LLM RESPONSE latencyMs={} canonical='{}' entities={} ordering={}",
        llmLatencyMs, llmResponse.getCanonicalTemplate(),
        llmResponse.getEntities().stream().map(e -> e.getName() + "='" + llmResponse.getExtractedFields().get(e.getName()) + "'").toList(),
        llmResponse.getOrdering());

// ... rest of existing save logic unchanged ...

boolean active = llmResponse.getConfidenceScore() >= confidenceThreshold;
// ... existing template save + entity update code ...

learningStateService.setState(senderId,
        active ? SenderLearningStateService.LearningState.LEARNED
               : SenderLearningStateService.LearningState.LEARNING_FAILED);
learningStateService.publishCompletion(senderId);
learningStateService.releaseLock(senderId);
```

- [ ] **Step 4: Compile to verify no errors**

```bash
mvn compile -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`

- [ ] **Step 5: Run full test suite**

```bash
mvn test -q 2>&1 | grep -E "Tests run:|BUILD" | tail -3
```
Expected: `BUILD SUCCESS`

---

### Task 4: Parallel batch processing in `SmsController`

**Files:**
- Modify: `src/main/java/com/sms/extraction/controller/SmsController.java`

**Interfaces:**
- Consumes: `SmsExtractionService.process(String senderId, String text)` — unchanged signature
- Produces: `/api/sms/batch` now processes all messages in parallel

- [ ] **Step 1: Update `SmsController.batch()` to use virtual threads**

Replace the current `batch()` method:

```java
@PostMapping("/batch")
public ResponseEntity<List<ExtractionResult>> batch(@RequestBody List<ProcessRequest> requests) {
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
        List<CompletableFuture<ExtractionResult>> futures = requests.stream()
                .map(r -> CompletableFuture.supplyAsync(
                        () -> smsExtractionService.process(r.senderId(), r.text()), executor))
                .toList();

        List<ExtractionResult> results = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        return ResponseEntity.ok(results);
    } finally {
        executor.close();
    }
}
```

Add imports:
```java
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
```

- [ ] **Step 2: Compile**

```bash
mvn compile -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Run full test suite**

```bash
mvn test -q 2>&1 | grep -E "Tests run:|BUILD" | tail -3
```
Expected: `BUILD SUCCESS`

---

### Task 5: Add `redis.learning.ttl-seconds` to `application.properties`

**Files:**
- Modify: `src/main/resources/application.properties`

- [ ] **Step 1: Add missing property**

```properties
redis.learning.ttl-seconds=120
```

- [ ] **Step 2: Final build + test**

```bash
mvn test -q 2>&1 | grep -E "Tests run:|BUILD" | tail -3
```
Expected: `BUILD SUCCESS`, all tests pass.

---

## Self-Review

**Spec coverage:**
- ✅ Redis pub/sub for waiting (RTopic in `SenderLearningStateServiceImpl`)
- ✅ `LEARNING_IN_PROGRESS / LEARNED / LEARNING_FAILED` states
- ✅ TTL = 2 minutes on `LEARNING_IN_PROGRESS`
- ✅ Wait → retry full extraction + template match
- ✅ Max 20 parallel LLM calls via `Semaphore`
- ✅ Virtual threads via `newVirtualThreadPerTaskExecutor()`
- ✅ Java 21 upgrade
- ✅ `spring.threads.virtual.enabled=true`
- ✅ Configurable max concurrent calls

**Placeholder scan:** None found — all steps contain full code.

**Type consistency:**
- `SenderLearningStateService.LearningState` enum referenced consistently across Task 2 + Task 3
- `learningStateService` field name consistent across constructor + method calls
- `llmSemaphore` field name consistent
- `retryAfterLearningComplete()` method signature matches its call site
