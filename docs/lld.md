# SMS Extraction Engine — Low Level Design

## Design Patterns Applied

| Pattern | Where |
|---|---|
| **Strategy** | `ExtractionStrategy` — REGEX and BOUNDARY_HINT are separate strategies |
| **Chain of Responsibility** | `TemplateMatchHandler` — Option 1 → Option 2, each passes on failure |
| **Decorator** | `CachedTemplateRepository`, `CachedEntityRepository` — cache wraps DynamoDB |
| **Observer** | `CacheUpdateListener` — instances react to DynamoDB writes via pub/sub |
| **Factory** | `CandidateTemplateFactory` — centralised construction with overlap validation |
| **Facade** | `SmsExtractionServiceImpl` — single entry point hiding all subsystem complexity |
| **Builder** | All domain objects |

---

## Class Diagram

```mermaid
classDiagram

    %% ════════════════════════════════════════════════════════
    %% ENUMS
    %% ════════════════════════════════════════════════════════

    class ExtractionRuleType {
        <<enumeration>>
        REGEX
        BOUNDARY_HINT
    }

    class LlmReason {
        <<enumeration>>
        NO_EXTRACTION_CANDIDATES
        NO_TEMPLATE_MATCH_BOTH_HASHES_MISSED
        BOUNDARY_VALIDATION_FAILED
        AMBIGUOUS_TEMPLATE_CONFLICT
        LOW_CONFIDENCE_TEMPLATE_INACTIVE
        EXTRACTION_CANDIDATES_DISCARDED
    }

    class LearningState {
        <<enumeration>>
        LEARNING_IN_PROGRESS
        LEARNED
        LEARNING_FAILED
    }

    %% ════════════════════════════════════════════════════════
    %% DOMAIN OBJECTS
    %% ════════════════════════════════════════════════════════

    class RegexVariant {
        -String regex
        -int group
        +getRegex() String
        +getGroup() int
    }

    class BoundaryPair {
        -String startAfter
        -String endBefore
        -int maxTokens
        +getStartAfter() String
        +getEndBefore() String
        +getMaxTokens() int
    }

    class GlobalEntity {
        -String name
        -ExtractionRuleType type
        -List~RegexVariant~ regexVariants
        -List~BoundaryPair~ boundaryPairs
        +getName() String
        +getType() ExtractionRuleType
        +getRegexVariants() List~RegexVariant~
        +getBoundaryPairs() List~BoundaryPair~
        +builder() Builder
    }


    class ExtractedValue {
        -String entityName
        -String value
        -int startPosition
        -int endPosition
        +getEntityName() String
        +getValue() String
        +getStartPosition() int
        +getEndPosition() int
        +builder() Builder
    }

    class CandidateTemplate {
        -List~ExtractedValue~ extractedValues
        -List~String~ entityNamesInOrder
        +getExtractedValues() List~ExtractedValue~
        +getEntityNamesInOrder() List~String~
        +builder() Builder
    }


    class EntitySnapshot {
        -String name
        -String semanticType
        -ExtractionRuleType type
        -String regex
        -int group
        -String startAfter
        -String endBefore
        -int maxTokens
        +getName() String
        +getSemanticType() String
        +builder() Builder
    }

    class LearnedTemplate {
        -String templateId
        -String senderId
        -String category
        -String subcategory
        -String intent
        -double confidenceScore
        -String canonicalTemplate
        -List~EntitySnapshot~ entitySnapshots
        -List~String~ ordering
        -String staticTextHash
        -String placeholderSequenceHash
        -boolean active
        +getTemplateId() String
        +isActive() boolean
        +builder() Builder
    }


    class TemplateMatchOutcome {
        -TemplateMatchResult matchResult
        -LlmReason failReason
        +isMatched() boolean
        +getMatchResult() Optional~TemplateMatchResult~
        +getFailReason() LlmReason
        +matched(TemplateMatchResult) TemplateMatchOutcome$
        +failed(LlmReason) TemplateMatchOutcome$
    }

    class TemplateMatchResult {
        -LearnedTemplate template
        -CandidateTemplate winningCandidate
        +getTemplate() LearnedTemplate
        +getWinningCandidate() CandidateTemplate
    }

    class LlmEntityInfo {
        -String name
        -String semanticType
        -ExtractionRuleType type
        -String regex
        -int group
        -String startAfter
        -String endBefore
        -int maxTokens
        +getName() String
        +getSemanticType() String
        +builder() Builder
    }

    class LlmResponse {
        -String category
        -String subcategory
        -String intent
        -double confidenceScore
        -Map~String,String~ extractedFields
        -String canonicalTemplate
        -List~LlmEntityInfo~ entities
        -List~String~ ordering
        -long inputTokens
        -long outputTokens
        -long cachedTokens
        -long reasoningTokens
        +getCategory() String
        +getEntities() List~LlmEntityInfo~
        +getOrdering() List~String~
        +builder() Builder
    }


    class ExtractionResult {
        -String messageId
        -String senderId
        -String rawSms
        -String normalizedSms
        -String templateId
        -String category
        -String subcategory
        -String intent
        -double confidenceScore
        -Map~String,String~ extractedEntities
        -Instant timestamp
        +getMessageId() String
        +getTemplateId() String
        +builder() Builder
    }

    class NormalizationRuleEntry {
        -String ruleType
        -Map~String,String~ mappings
        +getRuleType() String
        +getMappings() Map~String,String~
    }

    class HashUtil {
        +sha256(String) String$
        +staticTextHash(String, List~ExtractedValue~) String$
        +placeholderSequenceHash(List~String~) String$
    }

    %% ════════════════════════════════════════════════════════
    %% SERVICE INTERFACES
    %% ════════════════════════════════════════════════════════

    class SmsExtractionService {
        <<interface>>
        +process(String senderId, String rawSms) ExtractionResult
    }

    class NormalizationService {
        <<interface>>
        +normalize(String rawSms) String
        +isNonEnglish(String text) boolean
    }

    class EntityExtractionService {
        <<interface>>
        +extract(String normalizedSms, List~GlobalEntity~ entities) List~CandidateTemplate~
        +loadEntities(String senderId) List~GlobalEntity~
        +updateGlobalEntities(String senderId, List~GlobalEntity~ existing, LlmResponse llmResponse)
    }

    class TemplateMatchingService {
        <<interface>>
        +match(String normalizedSms, List~CandidateTemplate~ candidates, String senderId) TemplateMatchOutcome
        +saveTemplate(LearnedTemplate template)
        +existsById(String templateId) boolean
        +buildExtractedEntities(CandidateTemplate candidate, List~EntitySnapshot~ snapshots, String normalizedSms) Map~String,String~
    }

    class SenderLearningStateService {
        <<interface>>
        +tryClaimLearning(String senderId) boolean
        +acquireLock(String senderId)
        +releaseLock(String senderId)
        +setState(String senderId, LearningState state)
        +getState(String senderId) LearningState
        +waitForCompletion(String senderId)
        +publishCompletion(String senderId)
    }

    class LlmClient {
        <<interface>>
        +call(String normalizedSms, List~GlobalEntity~ existingEntities) LlmResponse
    }

    class ExtractionResultService {
        <<interface>>
        +save(ExtractionResult result)
    }

    %% ════════════════════════════════════════════════════════
    %% REPOSITORY INTERFACES
    %% ════════════════════════════════════════════════════════

    class TemplateRepository {
        <<interface>>
        +findById(String templateId) Optional~LearnedTemplate~
        +findByStaticTextHash(String senderId, String hash) Optional~LearnedTemplate~
        +findByPlaceholderSequenceHash(String senderId, String hash) List~LearnedTemplate~
        +save(LearnedTemplate template)
    }

    class EntityRepository {
        <<interface>>
        +findBySenderId(String senderId) List~GlobalEntity~
        +save(String senderId, List~GlobalEntity~ entities)
    }

    class NormalizationRuleRepository {
        <<interface>>
        +findAll() List~NormalizationRuleEntry~
        +save(NormalizationRuleEntry entry)
        +deleteByRuleType(String ruleType)
    }

    class ExtractionResultRepository {
        <<interface>>
        +save(ExtractionResult result)
    }

    %% ════════════════════════════════════════════════════════
    %% PATTERN: STRATEGY — Entity Extraction
    %% ════════════════════════════════════════════════════════

    class ExtractionStrategy {
        <<interface>>
        +supports(GlobalEntity entity) boolean
        +extract(String normalizedSms, GlobalEntity entity, ExtractionContext ctx) List~ExtractedValue~
    }

    class ExtractionContext {
        -Map~String,ExtractedValue~ resolved
        -Map~String,List~ExtractedValue~~ allOccurrences
        +isPositionClaimed(int position) boolean
        +isSpanClaimed(int start, int end) boolean
    }

    class RegexExtractionStrategy {
        +supports(GlobalEntity entity) boolean
        +extract(String normalizedSms, GlobalEntity entity, ExtractionContext ctx) List~ExtractedValue~
    }

    class BoundaryHintExtractionStrategy {
        +supports(GlobalEntity entity) boolean
        +extract(String normalizedSms, GlobalEntity entity, ExtractionContext ctx) List~ExtractedValue~
    }


    %% ════════════════════════════════════════════════════════
    %% PATTERN: CHAIN OF RESPONSIBILITY — Template Matching
    %% ════════════════════════════════════════════════════════

    class TemplateMatchHandler {
        <<abstract>>
        -TemplateMatchHandler next
        +setNext(TemplateMatchHandler next)
        +handle(String sms, CandidateTemplate candidate, String senderId) TemplateMatchOutcome
        #doMatch(String sms, CandidateTemplate candidate, String senderId) TemplateMatchOutcome*
    }

    class StaticHashMatchHandler {
        #doMatch(String sms, CandidateTemplate candidate, String senderId) TemplateMatchOutcome
    }

    class SequenceHashMatchHandler {
        #doMatch(String sms, CandidateTemplate candidate, String senderId) TemplateMatchOutcome
        -boundaryValidationPasses(String, LearnedTemplate, CandidateTemplate) boolean
    }


    %% ════════════════════════════════════════════════════════
    %% PATTERN: DECORATOR — Caching
    %% ════════════════════════════════════════════════════════

    class EntityCache {
        +get(String senderId) List~GlobalEntity~
        +put(String senderId, List~GlobalEntity~ entities)
        +evict(String senderId)
        +preload(Map~String,List~GlobalEntity~~ all)
    }

    class TemplateCache {
        +getByStaticHash(String senderId, String hash) Optional~LearnedTemplate~
        +getBySeqHash(String senderId, String hash) List~LearnedTemplate~
        +put(LearnedTemplate template)
        +preload(List~LearnedTemplate~ all)
    }

    class NormalizationRuleCache {
        +getRules() List~NormalizationRuleEntry~
        +update(List~NormalizationRuleEntry~ rules)
        +preload(List~NormalizationRuleEntry~ rules)
    }

    class CachedEntityRepository {
        <<decorator>>
        +findBySenderId(String senderId) List~GlobalEntity~
        +save(String senderId, List~GlobalEntity~ entities)
    }

    class CachedTemplateRepository {
        <<decorator>>
        +findById(String templateId) Optional~LearnedTemplate~
        +findByStaticTextHash(String senderId, String hash) Optional~LearnedTemplate~
        +findByPlaceholderSequenceHash(String senderId, String hash) List~LearnedTemplate~
        +save(LearnedTemplate template)
    }


    %% ════════════════════════════════════════════════════════
    %% PATTERN: OBSERVER — Cache Invalidation
    %% ════════════════════════════════════════════════════════

    class CacheUpdateListener {
        <<interface>>
        +onEntityUpdate(String senderId, List~GlobalEntity~ entities)
        +onTemplateUpdate(LearnedTemplate template)
        +onNormalizationRuleUpdate(List~NormalizationRuleEntry~ rules)
    }

    class InMemoryCacheUpdater {
        +onEntityUpdate(String senderId, List~GlobalEntity~ entities)
        +onTemplateUpdate(LearnedTemplate template)
        +onNormalizationRuleUpdate(List~NormalizationRuleEntry~ rules)
    }

    class RedisPubSubSubscriber {
        +subscribe()
    }


    %% ════════════════════════════════════════════════════════
    %% PATTERN: FACTORY — Candidate Construction
    %% ════════════════════════════════════════════════════════

    class CandidateTemplateFactory {
        <<factory>>
        +create(List~ExtractedValue~) CandidateTemplate$
        +empty() CandidateTemplate$
    }

    %% ════════════════════════════════════════════════════════
    %% SUPPORTING CLASSES
    %% ════════════════════════════════════════════════════════

    class LlmResponseMapper {
        +buildEntitySnapshots(LlmResponse) List~EntitySnapshot~
        +buildExtractedValuesFromLlm(LlmResponse, String normalizedSms) List~ExtractedValue~
        +buildExtractedEntitiesFromLlm(LlmResponse) Map~String,String~
    }

    class StartupCachePreloader {
        +preload()
    }

    %% ════════════════════════════════════════════════════════
    %% SERVICE IMPLEMENTATIONS
    %% ════════════════════════════════════════════════════════

    class SmsExtractionServiceImpl {
        <<facade>>
        -NormalizationService normalizationService
        -EntityExtractionService entityExtractionService
        -TemplateMatchingService templateMatchingService
        -LlmClient llmClient
        -LlmResponseMapper llmResponseMapper
        -ExtractionResultService extractionResultService
        -SenderLearningStateService learningStateService
        -Semaphore llmSemaphore
        -double confidenceThreshold
        +process(String senderId, String rawSms) ExtractionResult
        -retryAfterLearningComplete(String, String, String) ExtractionResult
    }

    class NormalizationServiceImpl {
        -NormalizationRuleCache ruleCache
        +normalize(String rawSms) String
        +isNonEnglish(String text) boolean
    }

    class EntityExtractionServiceImpl {
        -EntityRepository entityRepository
        -List~ExtractionStrategy~ strategies
        -CandidateTemplateFactory factory
        +extract(String normalizedSms, List~GlobalEntity~ entities) List~CandidateTemplate~
        +loadEntities(String senderId) List~GlobalEntity~
        +updateGlobalEntities(String senderId, List~GlobalEntity~ existing, LlmResponse) void
    }

    class TemplateMatchingServiceImpl {
        -TemplateMatchHandler handlerChain
        -TemplateRepository templateRepository
        +match(String, List~CandidateTemplate~, String) TemplateMatchOutcome
        +saveTemplate(LearnedTemplate template)
        +existsById(String templateId) boolean
        +buildExtractedEntities(CandidateTemplate, List~EntitySnapshot~, String) Map~String,String~
    }

    class SenderLearningStateServiceImpl {
        -RedissonClient redissonClient
        -int learningInProgressTtlSeconds
        +tryClaimLearning(String senderId) boolean
        +acquireLock(String senderId)
        +releaseLock(String senderId)
        +setState(String senderId, LearningState state)
        +getState(String senderId) LearningState
        +waitForCompletion(String senderId)
        +publishCompletion(String senderId)
    }

    class OpenAiLlmClient {
        -RestTemplate restTemplate
        -String apiKey
        -String model
        +call(String normalizedSms, List~GlobalEntity~ existingEntities) LlmResponse
    }

    class ExtractionResultServiceImpl {
        -ExtractionResultRepository extractionResultRepository
        +save(ExtractionResult result)
    }

    %% ── Implementations ──────────────────────────────────────

    %% ── Key dependencies (facade → services only) ────────────

    %% ── Impl internal dependencies ───────────────────────────

    %% ── Chain handlers → repository ──────────────────────────

    %% ════════════════════════════════════════════════════════
    %% REPOSITORY IMPLEMENTATIONS
    %% ════════════════════════════════════════════════════════

    class DynamoDbTemplateRepository {
        -DynamoDbClient dynamoDbClient
        +findById(String) Optional~LearnedTemplate~
        +findByStaticTextHash(String, String) Optional~LearnedTemplate~
        +findByPlaceholderSequenceHash(String, String) List~LearnedTemplate~
        +save(LearnedTemplate)
    }

    class DynamoDbEntityRepository {
        -DynamoDbClient dynamoDbClient
        +findBySenderId(String) List~GlobalEntity~
        +save(String, List~GlobalEntity~)
    }

    class DynamoDbNormalizationRuleRepository {
        -DynamoDbClient dynamoDbClient
        +findAll() List~NormalizationRuleEntry~
        +save(NormalizationRuleEntry)
        +deleteByRuleType(String)
    }

    class DynamoDbExtractionResultRepository {
        -DynamoDbClient dynamoDbClient
        +save(ExtractionResult)
    }


    %% Decorators wrap DynamoDB implementations

    %% ════════════════════════════════════════════════════════
    %% CONTROLLERS + KAFKA
    %% ════════════════════════════════════════════════════════

    class NormalizationRuleController {
        -NormalizationRuleRepository ruleRepository
        +getAll() ResponseEntity~List~NormalizationRuleEntry~~
        +save(NormalizationRuleEntry) ResponseEntity~Void~
        +delete(String ruleType) ResponseEntity~Void~
    }

    class SmsKafkaConsumer {
        -SmsExtractionService smsExtractionService
        -ObjectMapper objectMapper
        +consume(String message)
    }


```

---

## SOLID Principles — Applied Changes

### Single Responsibility
| What moved | From | To |
|---|---|---|
| `isNonEnglish()` | `SmsExtractionServiceImpl` | `NormalizationService` |
| `updateGlobalEntities()` | `SmsExtractionServiceImpl` | `EntityExtractionService` |
| `loadEntities()` | `SmsExtractionServiceImpl` | `EntityExtractionService` |
| `saveTemplate()` + `existsById()` | `SmsExtractionServiceImpl` | `TemplateMatchingService` |
| `buildExtractedEntities()` | `SmsExtractionServiceImpl` | `TemplateMatchingService` |
| `buildEntitySnapshots()` + `buildExtractedValuesFromLlm()` + `buildExtractedEntitiesFromLlm()` | `SmsExtractionServiceImpl` | `LlmResponseMapper` |
| `save(ExtractionResult)` | `SmsExtractionServiceImpl` | `ExtractionResultService` |
| REGEX extraction logic | `EntityExtractionServiceImpl` | `RegexExtractionStrategy` |
| BOUNDARY_HINT extraction logic | `EntityExtractionServiceImpl` | `BoundaryHintExtractionStrategy` |
| Option 1 hash match | `TemplateMatchingServiceImpl` | `StaticHashMatchHandler` |
| Option 2 hash match + boundary validation | `TemplateMatchingServiceImpl` | `SequenceHashMatchHandler` |

### Open/Closed
- New LLM provider → implement `LlmClient`
- New extraction type → implement `ExtractionStrategy`
- New matching strategy → extend `TemplateMatchHandler`
- New cache listener → implement `CacheUpdateListener`

### Liskov Substitution
- `CachedTemplateRepository` fully substitutes `DynamoDbTemplateRepository`
- `CachedEntityRepository` fully substitutes `DynamoDbEntityRepository`

### Interface Segregation
- Each repository interface has only the methods its consumers need
- `ExtractionResultService` shields service layer from repository layer

### Dependency Inversion
- `SmsExtractionServiceImpl` depends only on interfaces — no repositories, no implementations
- `EntityExtractionServiceImpl` depends on `ExtractionStrategy` interface
- `TemplateMatchingServiceImpl` depends on `TemplateMatchHandler` abstract class
- `RedisPubSubSubscriber` depends on `CacheUpdateListener` interface

