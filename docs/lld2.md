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

    %% ── ENUMS ────────────────────────────────────────────────────────────────────
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

    %% ── DOMAIN — ENTITY RULES ───────────────────────────────────────────────────
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

    GlobalEntity --> ExtractionRuleType
    GlobalEntity "1" *-- "0..*" RegexVariant
    GlobalEntity "1" *-- "0..*" BoundaryPair

    %% ── DOMAIN — EXTRACTION ─────────────────────────────────────────────────────
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

    CandidateTemplate "1" *-- "0..*" ExtractedValue

    %% ── DOMAIN — TEMPLATE ───────────────────────────────────────────────────────
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
        +getType() ExtractionRuleType
        +getRegex() String
        +getStartAfter() String
        +getEndBefore() String
        +builder() Builder
    }

    EntitySnapshot --> ExtractionRuleType

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
        +getSenderId() String
        +getEntitySnapshots() List~EntitySnapshot~
        +isActive() boolean
        +builder() Builder
    }

    LearnedTemplate "1" *-- "0..*" EntitySnapshot

    %% ── DOMAIN — TEMPLATE MATCH ─────────────────────────────────────────────────
    class TemplateMatchResult {
        -LearnedTemplate template
        -CandidateTemplate winningCandidate
        +getTemplate() LearnedTemplate
        +getWinningCandidate() CandidateTemplate
    }

    TemplateMatchResult --> LearnedTemplate
    TemplateMatchResult --> CandidateTemplate

    class TemplateMatchOutcome {
        -boolean matched
        -Optional~TemplateMatchResult~ matchResult
        -LlmReason failReason
        +isMatched() boolean
        +getMatchResult() Optional~TemplateMatchResult~
        +getFailReason() LlmReason
        +matched(TemplateMatchResult) TemplateMatchOutcome$
        +failed(LlmReason) TemplateMatchOutcome$
    }

    TemplateMatchOutcome --> TemplateMatchResult
    TemplateMatchOutcome --> LlmReason

    %% ── DOMAIN — LLM ─────────────────────────────────────────────────────────────
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

    LlmEntityInfo --> ExtractionRuleType

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

    LlmResponse "1" *-- "0..*" LlmEntityInfo

    %% ── DOMAIN — RESULT ─────────────────────────────────────────────────────────
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

    %% ── UTILITY ──────────────────────────────────────────────────────────────────
    class HashUtil {
        +sha256(String) String$
        +staticTextHash(String, List~ExtractedValue~) String$
        +placeholderSequenceHash(List~String~) String$
    }

    %% ── PATTERN: FACTORY ─────────────────────────────────────────────────────────
    %% SRP: CandidateTemplate construction + overlap validation extracted from
    %%      EntityExtractionServiceImpl into a dedicated factory
    class CandidateTemplateFactory {
        <<factory>>
        +create(List~ExtractedValue~) CandidateTemplate$
        +empty() CandidateTemplate$
    }

    CandidateTemplateFactory ..> CandidateTemplate : creates

    %% ── PATTERN: STRATEGY ────────────────────────────────────────────────────────
    %% OCP: new entity extraction types added by implementing ExtractionStrategy
    %%      without touching EntityExtractionServiceImpl
    %% SRP: REGEX and BOUNDARY_HINT logic fully separated
    class ExtractionStrategy {
        <<interface>>
        +supports(GlobalEntity entity) boolean
        +extract(String normalizedSms, GlobalEntity entity, ExtractionContext ctx) List~ExtractedValue~
    }

    class ExtractionContext {
        -Map~String,ExtractedValue~ resolved
        -Map~String,List~ExtractedValue~~ allOccurrences
        +getResolved() Map~String,ExtractedValue~
        +getAllOccurrences() Map~String,List~ExtractedValue~~
        +isPositionClaimed(int position) boolean
        +isSpanClaimed(int start, int end) boolean
    }

    class RegexExtractionStrategy {
        +supports(GlobalEntity entity) boolean
        +extract(String normalizedSms, GlobalEntity entity, ExtractionContext ctx) List~ExtractedValue~
        -extractVariant(String sms, String regex, int group) List~ExtractedValue~
    }

    class BoundaryHintExtractionStrategy {
        +supports(GlobalEntity entity) boolean
        +extract(String normalizedSms, GlobalEntity entity, ExtractionContext ctx) List~ExtractedValue~
        -extractBoundaryOccurrenceGroups(String, GlobalEntity, ExtractionContext) List~List~ExtractedValue~~
        -tryExtractBoundary(String, BoundaryPair, ExtractionContext, int) ExtractedValue
        -findTokenInText(String, String, int) int
        -greedyEndIndex(String, int, int) int
    }

    RegexExtractionStrategy ..|> ExtractionStrategy
    BoundaryHintExtractionStrategy ..|> ExtractionStrategy
    ExtractionStrategy ..> ExtractionContext

    %% ── PATTERN: CHAIN OF RESPONSIBILITY ────────────────────────────────────────
    %% OCP: new matching strategies added by extending TemplateMatchHandler
    %% SRP: Option 1 and Option 2 logic fully separated into dedicated handlers
    class TemplateMatchHandler {
        <<abstract>>
        -TemplateMatchHandler next
        +setNext(TemplateMatchHandler next)
        +handle(String sms, CandidateTemplate candidate, String senderId) TemplateMatchOutcome
        #doMatch(String sms, CandidateTemplate candidate, String senderId) TemplateMatchOutcome*
    }

    class StaticHashMatchHandler {
        -TemplateRepository templateRepository
        #doMatch(String sms, CandidateTemplate candidate, String senderId) TemplateMatchOutcome
    }

    class SequenceHashMatchHandler {
        -TemplateRepository templateRepository
        #doMatch(String sms, CandidateTemplate candidate, String senderId) TemplateMatchOutcome
        -boundaryValidationPasses(String, LearnedTemplate, CandidateTemplate) boolean
        -satisfiesBoundaryConditions(String, EntitySnapshot, ExtractedValue, CandidateTemplate, String) boolean
        -resolveMultipleMatches(List~LearnedTemplate~) Optional~LearnedTemplate~
    }

    StaticHashMatchHandler --|> TemplateMatchHandler
    SequenceHashMatchHandler --|> TemplateMatchHandler
    TemplateMatchHandler --> TemplateMatchHandler : next
    StaticHashMatchHandler --> TemplateRepository
    SequenceHashMatchHandler --> TemplateRepository

    %% ── PATTERN: OBSERVER ────────────────────────────────────────────────────────
    %% OCP: new cache types added by implementing CacheUpdateListener
    %% DIP: RedisPubSubSubscriber depends on abstraction not concrete cache
    class CacheUpdateListener {
        <<interface>>
        +onEntityUpdate(String senderId, List~GlobalEntity~ entities)
        +onTemplateUpdate(LearnedTemplate template)
        +onNormalizationRuleUpdate(List~NormalizationRuleEntry~ rules)
    }

    class InMemoryCacheUpdater {
        -EntityCache entityCache
        -TemplateCache templateCache
        -NormalizationRuleCache ruleCache
        +onEntityUpdate(String senderId, List~GlobalEntity~ entities)
        +onTemplateUpdate(LearnedTemplate template)
        +onNormalizationRuleUpdate(List~NormalizationRuleEntry~ rules)
    }

    class RedisPubSubSubscriber {
        -RedissonClient redissonClient
        -List~CacheUpdateListener~ listeners
        +subscribe()
        -notifyListeners(String channel, String payload)
    }

    InMemoryCacheUpdater ..|> CacheUpdateListener
    RedisPubSubSubscriber "1" --> "0..*" CacheUpdateListener : notifies

    %% ── PATTERN: DECORATOR ───────────────────────────────────────────────────────
    %% OCP: cache added without modifying DynamoDB implementations
    %% SRP: caching concern fully separated from persistence concern
    class EntityCache {
        -ConcurrentHashMap~String,List~GlobalEntity~~ store
        +get(String senderId) List~GlobalEntity~
        +put(String senderId, List~GlobalEntity~ entities)
        +evict(String senderId)
        +preload(Map~String,List~GlobalEntity~~ all)
    }

    class TemplateCache {
        -ConcurrentHashMap~String,LearnedTemplate~ byStaticHash
        -ConcurrentHashMap~String,List~LearnedTemplate~~ bySeqHash
        +getByStaticHash(String senderId, String hash) Optional~LearnedTemplate~
        +getBySeqHash(String senderId, String hash) List~LearnedTemplate~
        +put(LearnedTemplate template)
        +preload(List~LearnedTemplate~ all)
    }

    class NormalizationRuleCache {
        -List~NormalizationRuleEntry~ rules
        +getRules() List~NormalizationRuleEntry~
        +update(List~NormalizationRuleEntry~ rules)
        +preload(List~NormalizationRuleEntry~ rules)
    }

    class CachedEntityRepository {
        <<decorator>>
        -EntityRepository delegate
        -EntityCache cache
        +findBySenderId(String senderId) List~GlobalEntity~
        +save(String senderId, List~GlobalEntity~ entities)
    }

    class CachedTemplateRepository {
        <<decorator>>
        -TemplateRepository delegate
        -TemplateCache cache
        +findById(String templateId) Optional~LearnedTemplate~
        +findByStaticTextHash(String senderId, String hash) Optional~LearnedTemplate~
        +findByPlaceholderSequenceHash(String senderId, String hash) List~LearnedTemplate~
        +save(LearnedTemplate template)
    }

    CachedEntityRepository ..|> EntityRepository
    CachedEntityRepository --> EntityRepository : delegate
    CachedEntityRepository --> EntityCache
    CachedTemplateRepository ..|> TemplateRepository
    CachedTemplateRepository --> TemplateRepository : delegate
    CachedTemplateRepository --> TemplateCache
    InMemoryCacheUpdater --> EntityCache
    InMemoryCacheUpdater --> TemplateCache
    InMemoryCacheUpdater --> NormalizationRuleCache

    %% ── STARTUP PRELOADER ────────────────────────────────────────────────────────
    %% SRP: startup DynamoDB scan isolated from all runtime concerns
    class StartupCachePreloader {
        -TemplateRepository templateRepository
        -EntityRepository entityRepository
        -NormalizationRuleRepository ruleRepository
        -EntityCache entityCache
        -TemplateCache templateCache
        -NormalizationRuleCache ruleCache
        +preload()
    }

    StartupCachePreloader --> TemplateRepository
    StartupCachePreloader --> EntityRepository
    StartupCachePreloader --> NormalizationRuleRepository
    StartupCachePreloader --> EntityCache
    StartupCachePreloader --> TemplateCache
    StartupCachePreloader --> NormalizationRuleCache

    %% ── LLM RESPONSE MAPPER ──────────────────────────────────────────────────────
    %% SRP: all LLM response → domain object mapping extracted from
    %%      SmsExtractionServiceImpl into a dedicated mapper
    class LlmResponseMapper {
        +buildEntitySnapshots(LlmResponse) List~EntitySnapshot~
        +buildExtractedValuesFromLlm(LlmResponse, String normalizedSms) List~ExtractedValue~
        +buildExtractedEntitiesFromLlm(LlmResponse) Map~String,String~
    }

    LlmResponseMapper ..> LlmResponse
    LlmResponseMapper ..> EntitySnapshot
    LlmResponseMapper ..> ExtractedValue

    %% ── SERVICE INTERFACES ───────────────────────────────────────────────────────
    class SmsExtractionService {
        <<interface>>
        +process(String senderId, String rawSms) ExtractionResult
    }

    %% isNonEnglish moved here from SmsExtractionServiceImpl — SRP
    class NormalizationService {
        <<interface>>
        +normalize(String rawSms) String
        +isNonEnglish(String text) boolean
    }

    %% loadEntities + updateGlobalEntities moved here from SmsExtractionServiceImpl — SRP
    class EntityExtractionService {
        <<interface>>
        +extract(String normalizedSms, List~GlobalEntity~ entities) List~CandidateTemplate~
        +loadEntities(String senderId) List~GlobalEntity~
        +updateGlobalEntities(String senderId, List~GlobalEntity~ existing, LlmResponse llmResponse)
    }

    %% saveTemplate + existsById + buildExtractedEntities moved here — SRP
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
        +waitForCompletion(String senderId)
        +publishCompletion(String senderId)
        +setState(String senderId, LearningState state)
        +getState(String senderId) LearningState
        +acquireLock(String senderId)
        +releaseLock(String senderId)
    }

    SenderLearningStateService --> LearningState

    class LlmClient {
        <<interface>>
        +call(String normalizedSms, List~GlobalEntity~ existingEntities) LlmResponse
    }

    class ExtractionResultService {
        <<interface>>
        +save(ExtractionResult result)
    }

    %% ── REPOSITORY INTERFACES (ISP — each focused on one concern) ────────────────
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

    %% ── PATTERN: FACADE — SmsExtractionServiceImpl ───────────────────────────────
    %% After SOLID refactor:
    %% - No direct repository dependencies (DIP fixed)
    %% - No mapping/building logic (SRP fixed)
    %% - No isNonEnglish, no updateGlobalEntities (SRP fixed)
    %% - Pure orchestration only
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
        -buildResult(String, String, String, String, String, String, String, double, Map) ExtractionResult
    }

    SmsExtractionServiceImpl ..|> SmsExtractionService
    SmsExtractionServiceImpl --> NormalizationService
    SmsExtractionServiceImpl --> EntityExtractionService
    SmsExtractionServiceImpl --> TemplateMatchingService
    SmsExtractionServiceImpl --> LlmClient
    SmsExtractionServiceImpl --> LlmResponseMapper
    SmsExtractionServiceImpl --> ExtractionResultService
    SmsExtractionServiceImpl --> SenderLearningStateService

    %% ── SERVICE IMPLEMENTATIONS ──────────────────────────────────────────────────
    class NormalizationServiceImpl {
        -NormalizationRuleCache ruleCache
        +normalize(String rawSms) String
        +isNonEnglish(String text) boolean
    }

    NormalizationServiceImpl ..|> NormalizationService
    NormalizationServiceImpl --> NormalizationRuleCache

    class EntityExtractionServiceImpl {
        -EntityRepository entityRepository
        -List~ExtractionStrategy~ strategies
        -CandidateTemplateFactory factory
        +extract(String normalizedSms, List~GlobalEntity~ entities) List~CandidateTemplate~
        +loadEntities(String senderId) List~GlobalEntity~
        +updateGlobalEntities(String senderId, List~GlobalEntity~ existing, LlmResponse llmResponse)
        -cartesianProduct(List~List~ExtractedValue~~) List~List~ExtractedValue~~
        -generateNonOverlappingVariants(List~ExtractedValue~) List~List~ExtractedValue~~
        -powerSetSubsets(List~List~ExtractedValue~~) List~List~ExtractedValue~~
    }

    EntityExtractionServiceImpl ..|> EntityExtractionService
    EntityExtractionServiceImpl --> EntityRepository
    EntityExtractionServiceImpl "1" --> "0..*" ExtractionStrategy
    EntityExtractionServiceImpl --> CandidateTemplateFactory

    class TemplateMatchingServiceImpl {
        -TemplateMatchHandler handlerChain
        -TemplateRepository templateRepository
        +match(String, List~CandidateTemplate~, String) TemplateMatchOutcome
        +saveTemplate(LearnedTemplate template)
        +existsById(String templateId) boolean
        +buildExtractedEntities(CandidateTemplate, List~EntitySnapshot~, String) Map~String,String~
    }

    TemplateMatchingServiceImpl ..|> TemplateMatchingService
    TemplateMatchingServiceImpl --> TemplateMatchHandler
    TemplateMatchingServiceImpl --> TemplateRepository

    class SenderLearningStateServiceImpl {
        -RedissonClient redissonClient
        -int learningInProgressTtlSeconds
        +tryClaimLearning(String senderId) boolean
        +waitForCompletion(String senderId)
        +publishCompletion(String senderId)
        +setState(String senderId, LearningState state)
        +getState(String senderId) LearningState
        +acquireLock(String senderId)
        +releaseLock(String senderId)
    }

    SenderLearningStateServiceImpl ..|> SenderLearningStateService

    class OpenAiLlmClient {
        -RestTemplate restTemplate
        -ObjectMapper objectMapper
        -String apiKey
        -String model
        +call(String normalizedSms, List~GlobalEntity~ existingEntities) LlmResponse
        -buildSystemPrompt(List~GlobalEntity~) String
        -buildUserPrompt(String) String
        -parseResponse(String) LlmResponse
    }

    OpenAiLlmClient ..|> LlmClient

    class ExtractionResultServiceImpl {
        -ExtractionResultRepository extractionResultRepository
        +save(ExtractionResult result)
    }

    ExtractionResultServiceImpl ..|> ExtractionResultService
    ExtractionResultServiceImpl --> ExtractionResultRepository

    %% ── REPOSITORY IMPLEMENTATIONS ───────────────────────────────────────────────
    class DynamoDbTemplateRepository {
        -DynamoDbClient dynamoDbClient
        -String tableName
        +findById(String) Optional~LearnedTemplate~
        +findByStaticTextHash(String, String) Optional~LearnedTemplate~
        +findByPlaceholderSequenceHash(String, String) List~LearnedTemplate~
        +save(LearnedTemplate)
    }

    DynamoDbTemplateRepository ..|> TemplateRepository

    class DynamoDbEntityRepository {
        -DynamoDbClient dynamoDbClient
        -String tableName
        +findBySenderId(String) List~GlobalEntity~
        +save(String, List~GlobalEntity~)
    }

    DynamoDbEntityRepository ..|> EntityRepository

    class DynamoDbNormalizationRuleRepository {
        -DynamoDbClient dynamoDbClient
        -String tableName
        +findAll() List~NormalizationRuleEntry~
        +save(NormalizationRuleEntry)
        +deleteByRuleType(String)
    }

    DynamoDbNormalizationRuleRepository ..|> NormalizationRuleRepository

    class DynamoDbExtractionResultRepository {
        -DynamoDbClient dynamoDbClient
        -String tableName
        +save(ExtractionResult)
    }

    DynamoDbExtractionResultRepository ..|> ExtractionResultRepository

    %% Decorators wrap DynamoDB implementations
    CachedTemplateRepository --> DynamoDbTemplateRepository : delegate
    CachedEntityRepository --> DynamoDbEntityRepository : delegate

    %% ── CONTROLLERS ──────────────────────────────────────────────────────────────
    class NormalizationRuleController {
        -NormalizationRuleRepository ruleRepository
        +getAll() ResponseEntity~List~NormalizationRuleEntry~~
        +save(NormalizationRuleEntry) ResponseEntity~Void~
        +delete(String ruleType) ResponseEntity~Void~
    }

    NormalizationRuleController --> NormalizationRuleRepository

    %% ── KAFKA CONSUMER ───────────────────────────────────────────────────────────
    class SmsKafkaConsumer {
        -SmsExtractionService smsExtractionService
        -ObjectMapper objectMapper
        +consume(String message)
    }

    SmsKafkaConsumer --> SmsExtractionService
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
| Startup DynamoDB scan | scattered | `StartupCachePreloader` |
| Cache storage | scattered | `EntityCache`, `TemplateCache`, `NormalizationRuleCache` |

### Open/Closed
- New LLM provider → implement `LlmClient`, zero existing code changes
- New extraction type → implement `ExtractionStrategy`, register in `EntityExtractionServiceImpl`
- New matching strategy → extend `TemplateMatchHandler`, add to chain
- New cache listener → implement `CacheUpdateListener`, register in `RedisPubSubSubscriber`

### Liskov Substitution
- `CachedTemplateRepository` fully substitutes `DynamoDbTemplateRepository` — same interface, same contract
- `CachedEntityRepository` fully substitutes `DynamoDbEntityRepository`
- Any `ExtractionStrategy` substitutes any other without breaking `EntityExtractionServiceImpl`

### Interface Segregation
- `TemplateRepository` — focused on template persistence only
- `EntityRepository` — focused on entity persistence only
- `ExtractionResultRepository` — single `save` method only
- `CacheUpdateListener` — focused on cache update notifications only
- `ExtractionResultService` — single `save` method, shields service layer from repository layer

### Dependency Inversion
- `SmsExtractionServiceImpl` depends only on interfaces — no repositories, no implementations
- `EntityExtractionServiceImpl` depends on `ExtractionStrategy` interface — not on `RegexExtractionStrategy` directly
- `TemplateMatchingServiceImpl` depends on `TemplateMatchHandler` abstract — not on concrete handlers
- `RedisPubSubSubscriber` depends on `CacheUpdateListener` interface — not on `InMemoryCacheUpdater`
- `NormalizationServiceImpl` reads from `NormalizationRuleCache` — not from `NormalizationRuleRepository` directly
