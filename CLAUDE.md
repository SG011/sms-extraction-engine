# SMS Extraction Engine — Skills File

## What This Service Does

Extracts structured data (category, subcategory, intent, field values like amount, merchant,
reference ID) from raw SMS messages using an LLM-first approach.

LLM is called only the first time a new message shape is seen. Every subsequent message of
the same shape is handled by saved extraction rules — zero LLM cost, ~1ms.

---

## End to End Flow

### Step 1 — Ingestion
SMS arrives via Kafka consumer with `senderId` and raw message text.

### Step 2 — Normalization
Load global normalization rules from DynamoDB at runtime.

Rules are configurable and stored in DynamoDB — no code change required to update them:
- Keyword to canonical token mappings (e.g. "Rs." → "INR")
- Special character handling rules between digits
- Any other substitution or cleaning rules

Apply loaded rules to the raw SMS:
- Convert to uppercase
- Apply all loaded keyword to canonical token mappings
- Apply all loaded special character rules
- Keep dots elsewhere in the sentence
- Collapse multiple spaces

Normalization is deterministic — same input with same rules always produces same output.
All extraction rules and hashes operate on normalized text.

### Step 3 — Extraction
Load the global entity list for this `senderId` from DynamoDB.

Each entity contains:
- Entity name — unique per sender ID
- Regex pattern
- List of boundary pairs — `{ startAfter, endBefore }` pairs accumulated over time across
  all templates for this sender

Run all entity extraction rules against the normalized message in one pass:

- REGEX entities — run regex only against the normalized message. If regex matches → extract
  value. No boundary check at this stage.
- BOUNDARY_HINT entities — run startAfter/endBefore only. If both are present and satisfied
  in the message → extract the text between them. Greedy token fallback applies if endBefore
  is not found — take a configurable maximum number of words after startAfter. This limit is
  configurable per entity type.

Collect all successfully extracted field values with their entity names and positions.

Ambiguous regex matches:
If multiple REGEX entities match (e.g. both MERCHANT and LOCATION regex matched but neither
had boundary confirmation) → create separate candidate templates, one per combination. Each
candidate template is carried forward independently into template matching. First candidate
that finds a match wins.

If no values are extracted at all → skip to Step 5 (LLM Call).

### Step 4 — Template Matching

Run the following for each candidate template:

#### Option 1 — Static Text Hash Match
- Take the normalized message
- Remove all extracted field values
- Remove all spaces
- Hash the remaining static text
- Look up this hash in DynamoDB for this sender
- If found → go to Step 6 (Save and Return)
- If not found → go to Option 2

#### Option 2 — Placeholder Sequence Hash Match (Fallback)
- Take the entity names of all extracted fields in the order they appear in the message
- Example: [ACCT, AMOUNT]
- Hash this sequence
- Look up this hash in DynamoDB for this sender
- If not found → try next candidate template. If all candidates exhausted → go to Step 5 (LLM Call)
- If found → one or more templates match → go to Boundary Validation

#### Boundary Validation
- For each matching template retrieve its template-level entity snapshot — the exact
  startAfter and endBefore the LLM returned for that specific template
- Check whether those boundary conditions are satisfied in the current message for REGEX entities
- Discard any template where boundary conditions fail
- If no templates remain → try next candidate template. If all candidates exhausted → go to Step 5 (LLM Call)
- If one template remains → go to Step 6 (Save and Return)
- If multiple templates remain → check if category, subcategory, and intent are the same across all of them
  - If all agree → go to Step 6 (Save and Return)
  - If any conflict → go to Step 5 (LLM Call)

### Step 5 — LLM Call

Input to LLM:
- Normalized SMS
- Existing global entity list for this sender ID

LLM returns:
- Category, subcategory, intent
- Confidence score
- Extracted field values for this specific message
- Canonical template — normalized message with all variable parts replaced by named placeholders
- For each entity: name, regex pattern, startAfter, endBefore, maxTokens for greedy fallback
- Ordering of entities as they appear in the message

LLM entity naming rules enforced in prompt:
- If an entity already exists in the global list for this sender → reuse the name, return the
  new startAfter/endBefore, regex stays the same
- If a genuinely new entity is needed → assign a new unique name

Confidence score handling:
- Confidence >= 0.5 → save template and update global entity list → go to Step 6 (Save and Return)
- Confidence < 0.5 → save template to DynamoDB but exclude from template matching → go to Step 6 (Save and Return)

### Step 6 — Save and Return

Template record saved in DynamoDB (only when coming from Step 5):
- templateId = SHA256(senderId + "::" + canonicalTemplate)
- Category, subcategory, intent
- Confidence score
- Canonical template text
- Template-level entity snapshot — exact entity name, regex, startAfter, endBefore as
  returned by LLM for this template
- Ordering of entities
- Static text hash — normalized message with all extracted values and spaces removed, then
  hashed. Lookup target for Option 1
- Placeholder sequence hash — entity names in order of appearance, hashed. Lookup target for Option 2

Global entity table updated in DynamoDB (only when coming from Step 5):
- New entities → added to the global list
- Existing entities → new startAfter/endBefore pair appended to that entity's boundary list.
  Regex never overwritten

Extracted message result saved in DynamoDB (every message — whether matched via template or via LLM):
- PK: messageId (UUID)
- SK: senderId#timestamp
- Raw SMS, normalized SMS, templateId used, extracted field values, category, subcategory,
  intent, confidence score, timestamp

Extracted message result indexed in OpenSearch (every message):
- All extracted entities indexed as top-level fields for efficient querying
- Supports querying on any entity — MERCHANT, AMOUNT, CATEGORY, date ranges, full-text
  search on merchant names

Return result to caller.

---

## Key Design Decisions

### Two Extraction Rule Types
- REGEX — for structured tokens: amounts, OTPs, reference IDs, dates, masked account numbers.
  Boundary check happens during template matching, not during extraction.
- BOUNDARY_HINT — for free-form text: merchant names, locations, descriptions.
  startAfter/endBefore is the extraction mechanism itself.

### Two Levels of Entity Storage
- Template-level entity snapshot — exact LLM response for that specific template. Used for
  boundary validation during template matching.
- Global entity table per sender — consolidated list of all entities across all templates.
  Each entity accumulates multiple boundary pairs over time. Used for extraction in Step 3.

### Template Identity
templateId = SHA256(senderId + "::" + canonicalTemplate)
senderId is included so two banks with identical message structure get independent templates.
Canonical template is stored but NOT used for runtime matching — only for templateId
generation and deduplication.

### Two Hash Lookups
- Static text hash (Option 1) — hash of normalized message after removing all extracted
  values and spaces. Stored at template save time.
- Placeholder sequence hash (Option 2) — hash of entity names in order of appearance.
  Stored at template save time.

### Normalization Rules in DynamoDB
Global normalization rules are stored in DynamoDB and loaded at runtime. Zero code change
required to update rules.

### Confidence Score
- >= 0.5 → template saved and used for future matching
- < 0.5 → template saved but excluded from matching. Future messages re-enter LLM path.

---

## Storage

| What | Where | Key |
|---|---|---|
| Global normalization rules | DynamoDB | `normalization:global` |
| Global entity list per sender | DynamoDB | `entities:{senderId}` |
| Template records | DynamoDB | `templateId` = SHA256(senderId + "::" + canonicalTemplate) |
| Extracted message results | DynamoDB | PK: messageId, SK: senderId#timestamp |
| Queryable extracted results | OpenSearch | All entities as top-level fields |

---

## Tech Stack

- Java 17
- Spring Boot 3.x
- Apache Kafka (consumer only)
- AWS DynamoDB (via AWS SDK v2)
- OpenSearch (via OpenSearch Java client)
- OpenAI GPT-4o (LLM)
- SHA-256 for hashing

---

## DynamoDB Tables

### normalization-rules
- PK: `ruleType` (e.g. "KEYWORD_MAPPING", "SPECIAL_CHAR")
- Attributes: rules as a list

### sender-entities
- PK: `senderId`
- Attributes: list of entities, each with name, regex, list of { startAfter, endBefore, maxTokens }

### templates
- PK: `templateId` (SHA256)
- Attributes: senderId, category, subcategory, intent, confidenceScore, canonicalTemplate,
  entitySnapshot, ordering, staticTextHash, placeholderSequenceHash, active (boolean)

### extracted-messages
- PK: `messageId` (UUID)
- SK: `senderId#timestamp`
- Attributes: rawSms, normalizedSms, templateId, extractedEntities (map), category,
  subcategory, intent, confidenceScore, timestamp

---

## Coding Rules

- Constructor injection only. Never @Autowired on a field.
- Depend on interfaces, not implementations.
- No instanceof checks. No switch on category strings.
- No hardcoded regex patterns outside the normalization layer.
- No in-process caches (Caffeine etc.) — all caching via DynamoDB.
- All DynamoDB access via repository interfaces.
- All LLM interaction via a dedicated client interface.
- Extraction rules and template matching are independent services — no coupling between them.

---

## Key Classes

- `SmsExtractionService` — main orchestration, entry point for each SMS
- `NormalizationService` — loads rules from DynamoDB, applies to raw SMS
- `EntityExtractionService` — loads global entity list, runs regex and boundary extraction
- `TemplateMatchingService` — Option 1, Option 2, boundary validation
- `LlmClient` — interface wrapping OpenAI call, returns `LlmResponse`
- `TemplateRepository` — DynamoDB read/write for templates
- `EntityRepository` — DynamoDB read/write for global entity list per sender
- `NormalizationRuleRepository` — DynamoDB read/write for normalization rules
- `ExtractionResultRepository` — DynamoDB write for extracted message results
- `OpenSearchIndexer` — indexes extracted results into OpenSearch
- `ExtractionRule` — REGEX or BOUNDARY_HINT rule type
- `LearnedTemplate` — what gets saved after LLM runs
- `CandidateTemplate` — intermediate object during template matching

---

## Adding Support for a New Message Type

Zero code changes required. The LLM automatically handles new message types the first time
it sees them. New categories, new entity types — all emerge from LLM responses and are saved
automatically.

---

## Configuration

```properties
openai.api-key=...
openai.model=gpt-4o
openai.timeout-seconds=10

aws.region=ap-south-1
dynamodb.endpoint=...

opensearch.host=...
opensearch.port=443

kafka.bootstrap-servers=...
kafka.topic=sms-inbound
kafka.group-id=sms-extraction-engine

template.confidence-threshold=0.50
```
