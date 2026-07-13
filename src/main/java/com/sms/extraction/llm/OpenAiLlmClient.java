package com.sms.extraction.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sms.extraction.domain.ExtractionRuleType;
import com.sms.extraction.domain.GlobalEntity;
import com.sms.extraction.domain.LlmEntityInfo;
import com.sms.extraction.domain.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class OpenAiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlmClient.class);

    private static final String OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;

    public OpenAiLlmClient(RestTemplate restTemplate,
                            ObjectMapper objectMapper,
                            @Value("${openai.api-key}") String apiKey,
                            @Value("${openai.model:gpt-4o}") String model) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public LlmResponse call(String normalizedSms, List<GlobalEntity> existingEntities) {
        String systemPrompt = buildSystemPrompt(existingEntities);
        String userPrompt = buildUserPrompt(normalizedSms);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        // gpt-5.x models do not support temperature=0, only default (1) is allowed
        if (!model.startsWith("gpt-5")) {
            requestBody.put("temperature", 0.0);
        }
        requestBody.put("response_format", Map.of("type", "json_object"));

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", userPrompt));
        requestBody.put("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        int attempts = 0;
        while (true) {
            try {
                String responseBody = restTemplate.postForObject(OPENAI_CHAT_URL, request, String.class);
                return parseResponse(responseBody);
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429 && attempts < 4) {
                    long waitMs = (long) Math.pow(2, attempts) * 1000;
                    log.warn("OpenAI rate limited, retrying in {}ms (attempt {})", waitMs, attempts + 1);
                    try { Thread.sleep(waitMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    attempts++;
                } else {
                    log.error("Error calling OpenAI API: {}", e.getMessage());
                    throw new RuntimeException("LLM call failed", e);
                }
            } catch (Exception e) {
                log.error("Error calling OpenAI API", e);
                throw new RuntimeException("LLM call failed", e);
            }
        }
    }

    private String buildSystemPrompt(List<GlobalEntity> existingEntities) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an SMS data extraction engine. Extract structured data from normalized SMS messages.\n\n");

        sb.append("TASK:\n");
        sb.append("Given a normalized SMS message, extract:\n");
        sb.append("1. category - high-level category (e.g. BANKING, ECOMMERCE, TELECOM, UTILITY)\n");
        sb.append("2. subcategory - specific type (e.g. DEBIT, CREDIT, OTP, RECHARGE, ORDER)\n");
        sb.append("3. intent - action intent (e.g. TRANSACTION_ALERT, OTP_DELIVERY, ORDER_CONFIRMATION)\n");
        sb.append("4. confidenceScore - confidence in extraction (0.0 to 1.0)\n");
        sb.append("5. extractedFields - map of entity name to extracted value\n");
        sb.append("6. canonicalTemplate - the normalized SMS with all variable parts replaced by {ENTITY_NAME} placeholders\n");
        sb.append("7. entities - list of entity extraction rules\n");
        sb.append("8. ordering - list of entity names in order of appearance in the message\n\n");

        sb.append("ENTITY TYPES:\n");
        sb.append("- REGEX: ONLY for structured, predictable tokens with a fixed pattern — amounts, OTPs, reference IDs, dates, masked account numbers, card numbers, PINs, VPAs.\n");
        sb.append("- BOUNDARY_HINT: for ALL free-form text — merchant names, beneficiary names, location names, descriptions, product names, any entity whose value varies unpredictably.\n");
        sb.append("CRITICAL: MERCHANT, BENEFICIARY, DESCRIPTION, LOCATION, REMITTER_NAME, PAYEE — these MUST always be BOUNDARY_HINT, NEVER REGEX.\n");
        sb.append("A regex like '(?<=AT )[A-Z0-9]+' for a merchant is WRONG — it will match URLs and other tokens. Use startAfter/endBefore positional anchors instead.\n\n");

        sb.append("ENTITY EXTRACTION SCOPE (CRITICAL):\n");
        sb.append("Only include an entity in the canonical template, entities array, and ordering if its value is ACTUALLY PRESENT and EXTRACTABLE from the current message.\n");
        sb.append("Do NOT include entities from the global entity list that have no matching value in this message — even if those entities exist for this sender.\n");
        sb.append("Example: global list has INTEREST_RATE, but this message says 'LATE PAYMENT CHARGES' with no percentage → do NOT include INTEREST_RATE.\n");
        sb.append("Example: global list has a second AMOUNT for balance, but this message has no balance → only include AMOUNT once.\n");
        sb.append("Each message may represent a lighter or richer variant of the same template family. Each variant must produce its own canonical template with only the entities present in IT.\n\n");
        sb.append("ENTITY NAMING RULES (CRITICAL):\n");
        sb.append("- If an entity already exists in the global entity list, REUSE that exact name.\n");
        sb.append("- REGEX check: verify whether the existing regex actually matches the value in the CURRENT message.\n");
        sb.append("  - If the existing regex MATCHES the value → keep the same regex unchanged.\n");
        sb.append("  - If the existing regex does NOT match the value (different format, different separator, different prefix) → provide a NEW regex that correctly matches this format. The system will store both as variants.\n");
        sb.append("  Example: entity REFERENCE_ID exists with regex '(?<=UPI:)[0-9]+' but current message has 'UPI-123456' (hyphen not colon) → return regex '(?<=UPI-)[0-9]+' for this message.\n");
        sb.append("- BOUNDARY_HINT check: for existing BOUNDARY_HINT entities, check whether any stored startAfter/endBefore boundary pair can locate a value in the CURRENT message.\n");
        sb.append("  - If an existing boundary pair fires (startAfter token present in message AND endBefore token present after it) → use EXACTLY those same startAfter and endBefore values. Do NOT invent new boundaries.\n");
        sb.append("  - Only define NEW boundaries if NO existing boundary pair fires on this message.\n");
        sb.append("  - NEVER change which occurrence of a concept to extract — always anchor to the same structural position as the existing boundary pair.\n");
        sb.append("  Example: SERVICE_NAME exists with startAfter='FOR', endBefore='VRN'. Current message has 'For IDFC FIRST Bank FASTag VRN' → extract 'IDFC FIRST BANK FASTAG' using startAfter='FOR', endBefore='VRN'. Do NOT switch to extracting 'MAX SUBSCRIPTION' from a different position.\n");
        sb.append("- If the SAME entity concept appears MULTIPLE TIMES in the message (e.g. transaction amount AND available balance are both amounts), use the SAME entity name for both but give each a unique semanticType.\n");
        sb.append("  CRITICAL: if the entity appears N times in the message, the entities array MUST contain EXACTLY N separate entries for that entity — one per occurrence.\n");
        sb.append("  Each entry must have its own startAfter/endBefore matching its position in the message. For the regex: if the two occurrences have different surrounding context (different lookbehind), provide a distinct regex for each; if they share the same pattern, the same regex is fine.\n");
        sb.append("  The ordering array must list the entity name N times, once per occurrence in message order.\n");
        sb.append("  This is mandatory even if the entity already exists in the global list — the second occurrence still needs its own entry with its own regex.\n");
        sb.append("  Example: message 'DEBITED INR 1000.00... BAL INR 500.00' → AMOUNT appears twice:\n");
        sb.append("    {name: AMOUNT, semanticType: TRANSACTION_AMOUNT, regex: '(?<=DEBITED INR )[0-9]+(?:\\.[0-9]{1,2})?', startAfter: 'DEBITED INR', endBefore: '...'}\n");
        sb.append("    {name: AMOUNT, semanticType: AVAILABLE_BALANCE, regex: '(?<=BAL INR )[0-9]+(?:\\.[0-9]{1,2})?',    startAfter: 'BAL INR',     endBefore: '.'}\n");
        sb.append("  ordering: ['AMOUNT', 'AMOUNT']\n");
        sb.append("  Both entries are saved as separate regex variants for the AMOUNT entity so future messages can extract both values.\n");
        sb.append("  CRITICAL — DO NOT use indexed names like SECURITY_QUANTITY_1, SECURITY_QUANTITY_2, SECURITY_NAME_1, SECURITY_NAME_2.\n");
        sb.append("  This applies to ALL repeating structures — securities, items, installments, fund names, etc.\n");
        sb.append("  Example: message '650 shares of ICICI FUND, 150 shares of HDFC FUND on 16/06/2026' → QUANTITY and SECURITY_NAME each appear twice:\n");
        sb.append("    {name: QUANTITY, semanticType: SECURITY_QUANTITY_1, regex: '(?<=FOR )[0-9]+(?= SHARES)', startAfter: 'FOR', endBefore: 'SHARES'}\n");
        sb.append("    {name: SECURITY_NAME, semanticType: SECURITY_NAME_1, type: BOUNDARY_HINT, startAfter: 'SHARES OF', endBefore: ','}\n");
        sb.append("    {name: QUANTITY, semanticType: SECURITY_QUANTITY_2, regex: '(?<=, )[0-9]+(?= SHARES)', startAfter: ',', endBefore: 'SHARES'}\n");
        sb.append("    {name: SECURITY_NAME, semanticType: SECURITY_NAME_2, type: BOUNDARY_HINT, startAfter: 'SHARES OF', endBefore: 'ON'}\n");
        sb.append("  ordering: ['QUANTITY', 'SECURITY_NAME', 'QUANTITY', 'SECURITY_NAME', 'DATE']\n");
        sb.append("  WRONG: ordering: ['SECURITY_QUANTITY_1', 'SECURITY_NAME_1', 'SECURITY_QUANTITY_2', 'SECURITY_NAME_2', 'DATE'] — never use indexed names.\n");
        sb.append("\n");
        sb.append("REPEATING ENTITY RULES (CRITICAL — read carefully before writing regex or boundaries for any repeated entity):\n");
        sb.append("These rules apply whenever the same entity name appears MORE THAN ONCE in the canonical template.\n");
        sb.append("\n");
        sb.append("RULE A — REGEX for Nth occurrence: the lookbehind MUST use only the fixed structural separator between occurrences, NOT the variable entity text preceding it.\n");
        sb.append("The variable entity value (a security name, item description, company name) changes per message — including it in the lookbehind makes the regex message-specific and useless for all other messages.\n");
        sb.append("  Message: '...DEBITED WITH 10 SHARES OF RELIANCE INDUSTRIES LTD AND 5 SHARES OF INFOSYS LTD ON...'\n");
        sb.append("  Second QUANTITY regex: WRONG '(?<=LTD AND )[0-9]+' — 'LTD' is part of the variable security name; another message may have 'TATA CONSULTANCY SERVICES AND 15...' with no 'LTD'\n");
        sb.append("  Second QUANTITY regex: RIGHT  '(?<=AND )[0-9]+' — 'AND' is the fixed structural separator, works for any security name before it\n");
        sb.append("\n");
        sb.append("RULE B — BOUNDARY_HINT for Nth occurrence: use the SAME structural anchor phrase as startAfter for ALL occurrences of the entity.\n");
        sb.append("The engine iterates through every occurrence of startAfter automatically (while-loop). Give each occurrence a different endBefore to delimit its extent.\n");
        sb.append("  First occurrence:  startAfter='SHARES OF', endBefore='AND'  → extracts the security name before 'AND'\n");
        sb.append("  Second occurrence: startAfter='SHARES OF', endBefore='ON'   → engine finds the NEXT 'SHARES OF', extracts until 'ON'\n");
        sb.append("  DO NOT use the inter-occurrence separator ('AND') alone as startAfter for a later occurrence.\n");
        sb.append("  WHY: 'AND' is immediately followed by the next QUANTITY (a REGEX entity). REGEX entities claim their positions. Any startAfter that puts the extraction start ON a claimed position will silently fail — the engine skips claimed positions and finds no valid start.\n");
        sb.append("  WRONG: second SECURITY_NAME startAfter='AND' — after 'AND' is '5' (the second QUANTITY, regex-claimed) → extraction fails, entity is never found\n");
        sb.append("  RIGHT:  second SECURITY_NAME startAfter='SHARES OF' — after second 'SHARES OF' is the security name itself (not claimed) → extraction succeeds\n");
        sb.append("\n");
        sb.append("RULE C — check for claimed positions: before setting startAfter for a BOUNDARY_HINT entity, verify what immediately follows startAfter in the message.\n");
        sb.append("If the text immediately after startAfter is a REGEX entity value (a number, date, account number), that position is claimed — the extraction will fail.\n");
        sb.append("Choose a startAfter that lands AFTER all REGEX entities, directly before the free-form text you want to extract.\n");
        sb.append("- Only assign a new unique name if the entity concept is genuinely new and not represented in the existing list.\n");
        sb.append("- Use uppercase underscore-separated names (e.g. AMOUNT, MERCHANT, ACCOUNT_NUMBER, OTP, REFERENCE_ID)\n\n");

        sb.append("ENTITY FIELDS:\n");
        sb.append("Each entity in the 'entities' array must have:\n");
        sb.append("- name: entity name (generic concept name like AMOUNT, DATE, MERCHANT)\n");
        sb.append("- semanticType: specific semantic label for this occurrence (e.g. TRANSACTION_AMOUNT, AVAILABLE_LIMIT, DUE_DATE). If the entity appears only once, semanticType = name. If it appears multiple times, each gets a unique semanticType.\n");
        sb.append("- type: REGEX or BOUNDARY_HINT\n");
        sb.append("- regex: regex pattern (required for REGEX type, null for BOUNDARY_HINT)\n");
        sb.append("- group: capturing group number to extract from the regex match (default 0 = full match).\n");
        sb.append("  Use group > 0 when surrounding context is needed in the regex for disambiguation, but only part of the match is the actual value.\n");
        sb.append("  Example: pattern \"AVL INR ([0-9]+\\\\.[0-9]{2})\" with group 1 matches the full \"AVL INR 9493.09\" but extracts only \"9493.09\".\n");
        sb.append("  When using group > 0, do NOT use a lookbehind — the capturing group replaces it.\n");
        sb.append("- startAfter: the boundary before the value — a fixed structural keyword (e.g. \"INR\", \"CARD\") OR {ENTITY_NAME} if the preceding token is another entity's value OR \"SOM\" if the value appears at the very start of the message.\n");
        sb.append("  CRITICAL: use the most specific multi-word phrase possible as startAfter. NEVER use a single generic word (FOR, TO, BY, OF, IN, AT, ON, IS, A, THE) alone — these appear too frequently and will anchor to the wrong position.\n");
        sb.append("  RIGHT: \"TOWARDS\" is ok if unique, but \"CREATED TOWARDS\" is better. \"IF NOT YOU -\" is good. \"FOR\" alone is wrong.\n");
        sb.append("  CRITICAL: if a company/brand name or variable entity name immediately precedes the startAfter anchor word, include the FULL name in startAfter — do NOT use only the last word of it.\n");
        sb.append("  Example: message has 'RAISESECURITIESPRIVATELIMITED ON 07-03-2026' — startAfter='LIMITED ON' is WRONG because 'LIMITED' is part of the concatenated company name, not a standalone word. Use startAfter='RAISESECURITIESPRIVATELIMITED ON' (full name) OR use {BROKER_NAME} entity reference if the company name is extracted as an entity.\n");
        sb.append("  Use startAfter=\"SOM\" when the entity value appears at the very beginning of the message with no preceding context.\n");
        sb.append("- endBefore: the boundary after the value — a fixed structural keyword OR {ENTITY_NAME} if the following token is another entity's value OR \"EOM\" if the value extends to the very end of the message.\n");
        sb.append("  Leave endBefore empty (\"\") when there is no reliable fixed token after the value — the engine will use maxTokens as a word limit instead.\n");
        sb.append("  Use endBefore=\"EOM\" when the entity value runs to the end of the message with nothing following it.\n");
        sb.append("- maxTokens: maximum words to capture greedily when endBefore is empty or not found in the message (default 3 for BOUNDARY_HINT)\n\n");
        sb.append("ENTITY-REFERENCE BOUNDARIES:\n");
        sb.append("When startAfter or endBefore is the value of another entity (variable per message — e.g. a date, time, merchant name), write it as {ENTITY_NAME} with curly braces.\n");
        sb.append("This applies to BOTH REGEX and BOUNDARY_HINT entities.\n");
        sb.append("CRITICAL: {ENTITY_NAME} MUST be the ENTIRE startAfter or endBefore value — no leading or trailing characters.\n");
        sb.append("The engine positions immediately after the named entity's extracted value, so any separator (like '-', ':', ' ') is handled automatically.\n");
        sb.append("  WRONG: \"{REFERENCE_ID}-\"  — trailing hyphen makes this a literal string, the engine cannot resolve it.\n");
        sb.append("  WRONG: \"UPI-{REFERENCE_ID}\" — leading text makes this a literal string.\n");
        sb.append("  RIGHT: \"{REFERENCE_ID}\"    — pure entity reference, engine resolves to the position right after REFERENCE_ID's value.\n");
        sb.append("CRITICAL: Use {ENTITY_NAME} ONLY when the entity value is the DIRECT IMMEDIATE predecessor/successor with NO static text between them.\n");
        sb.append("If there is ANY static word between the entity and the target value, use that static word as startAfter instead.\n");
        sb.append("  WRONG: template is 'INR {AMOUNT} AT {MERCHANT}' → startAfter: \"{AMOUNT}\" — WRONG because 'AT' sits between AMOUNT and MERCHANT.\n");
        sb.append("  RIGHT: template is 'INR {AMOUNT} AT {MERCHANT}' → startAfter: \"AT\" — use the static word 'AT' that immediately precedes MERCHANT.\n");
        sb.append("  RIGHT: template is 'UPI-{REFERENCE_ID}-{MERCHANT}' → startAfter: \"{REFERENCE_ID}\" — no static text between REFERENCE_ID and MERCHANT, only a hyphen which the engine handles automatically.\n");
        sb.append("Examples:\n");
        sb.append("  MERCHANT between '@' and DATE → startAfter: \"@\", endBefore: \"{DATE}\"\n");
        sb.append("  DATE between MERCHANT and TIME → startAfter: \"{MERCHANT}\", endBefore: \"{TIME}\"\n");
        sb.append("  TIME starting after DATE → startAfter: \"{DATE}\", endBefore: \".\"\n");
        sb.append("  MERCHANT after REFERENCE_ID (e.g. UPI-{REFERENCE_ID}-{MERCHANT}) → startAfter: \"{REFERENCE_ID}\", endBefore: \".\"\n");
        sb.append("The engine resolves {DATE} to wherever DATE was extracted in this message at runtime.\n");
        sb.append("NEVER use a specific variable value (e.g. \"15-02-2026\", \"URI\", \"500.00\") as startAfter or endBefore — it only matches one message and breaks on all others.\n\n");

        sb.append("REGEX RULES (CRITICAL — read carefully):\n");
        sb.append("1. Every REGEX entity must have a lookbehind anchor at the start of the regex.\n");
        sb.append("   Use Java regex lookbehind syntax: (?<=fixed text ) before the value pattern.\n");
        sb.append("   This anchors the regex to the exact position in the message so it does not match the wrong occurrence.\n");
        sb.append("   WRONG: \"[0-9]+(?:\\.[0-9]{1,2})?\"  — matches every number in the message\n");
        sb.append("   RIGHT: \"(?<=INR )[0-9]+(?:\\.[0-9]{1,2})?\"  — matches only the number after 'INR'\n");
        sb.append("   RIGHT: \"(?<=LMT: INR )[0-9]+(?:\\.[0-9]{1,2})?\"  — matches only the available limit\n");
        sb.append("2. The lookbehind text MUST be fixed-length — no quantifiers (+, *, {n,m}, ?) inside the lookbehind.\n");
        sb.append("   WRONG: (?<=ON [0-9]{2}-[0-9]{2}-[0-9]{4} )  — variable-length lookbehind, Java will reject it\n");
        sb.append("   RIGHT: (?<=PM )  — fixed-length, use the token immediately before the value\n");
        sb.append("3. NEVER use capturing groups () anywhere in the regex. The engine calls matcher.group() which returns the full match.\n");
        sb.append("   WRONG: \"(?<=INR )([0-9]+)\"\n");
        sb.append("   RIGHT: \"(?<=INR )[0-9]+\"\n");
        sb.append("4. The lookbehind anchors the regex — but startAfter must also be set for template matching boundary validation.\n");
        sb.append("   If the token immediately before the value is a fixed structural word, set startAfter to that word (same as the lookbehind text).\n");
        sb.append("   If the token immediately before the value is another entity's value (variable), set startAfter to {ENTITY_NAME} instead.\n");
        sb.append("   NEVER use a specific variable value (e.g. a date, amount, merchant name) as startAfter — use {ENTITY_NAME}.\n");
        sb.append("5. Use precise digit counts. Dates have 4-digit years: [0-9]{2}-[0-9]{2}-[0-9]{4}. Masked accounts: XX[0-9]{4}.\n");
        sb.append("6. NEVER assign the same regex to two different entities. If the same pattern appears multiple times in the message\n");
        sb.append("   for different entities (e.g. INR appears before both a transaction amount and an available limit), each entity\n");
        sb.append("   MUST have a unique lookbehind that distinguishes it from the others.\n");
        sb.append("   WRONG: AMOUNT uses \"(?<=INR )[0-9]+\" AND AVAILABLE_LIMIT also uses \"(?<=INR )[0-9]+\" — identical, causes conflicts\n");
        sb.append("   RIGHT: AMOUNT uses \"(?<=SPENT INR )[0-9]+\" AND AVAILABLE_LIMIT uses \"(?<=AVL LIMIT: INR )[0-9]+\"\n");
        sb.append("   Use the longest specific phrase before the value as the lookbehind to uniquely identify each occurrence.\n");
        sb.append("7. The lookbehind MUST contain at least 2 words of context. A single-word lookbehind is almost always too broad.\n");
        sb.append("   Single words appear as substrings of longer words and cause false matches on unrelated messages.\n");
        sb.append("   WRONG: \"(?<=TAG )[0-9A-Z]+\" — 'TAG' is a suffix of 'FASTAG', this will match 'ON' in 'FASTAG ON GJ01...'\n");
        sb.append("   RIGHT: \"(?<=BANK TAG )[0-9A-Z]+\" — two words, unambiguous, cannot match inside a compound word\n");
        sb.append("   WRONG: \"(?<=FOR )[A-Z]+\" — 'FOR' is too generic, matches in dozens of positions\n");
        sb.append("   RIGHT: \"(?<=REF NO: )[A-Z0-9]+\" — specific multi-word phrase before the value\n\n");

        sb.append("EXTRACTION SCOPE — only extract entities that carry meaningful per-transaction information:\n");
        sb.append("✓ EXTRACT: transaction amounts, account numbers, dates, merchant names, UPI/IMPS reference IDs,\n");
        sb.append("  available balance/limit, OTPs, beneficiary names, VPA addresses, loan details.\n");
        sb.append("✓ EXTRACT URLs: if a message contains a URL (e.g. https://indusbk.in/INDUSB/WSX3EFD), extract the\n");
        sb.append("  ENTIRE URL as a REGEX entity named URL. Use a regex with a lookbehind anchored to the fixed text\n");
        sb.append("  immediately before it (e.g. '(?<=Click to proceed: )https://\\S+' or '(?<=at: )https://\\S+').\n");
        sb.append("  This ensures different per-message URL variants map to the same canonical template {URL}.\n");
        sb.append("✗ DO NOT EXTRACT:\n");
        sb.append("  - Helpline/dispute phone numbers (e.g. 18002662, 18001080)\n");
        sb.append("  - SMS blocking codes (e.g. 'SMS BLOCK 036', 'SMS BLOCK 5004') — these are static instructions\n");
        sb.append("  - Marketing/promotional text\n");
        sb.append("  - Static boilerplate that is identical across all messages from a sender\n");
        sb.append("  - UPI transaction strings in the format 'UPI [alphanumeric_code] [numeric_reference]' must NOT be classified as MERCHANT or BENEFICIARY.\n");
        sb.append("    e.g. 'UPI M2SKNKPGQ 648600156354' — 'M2SKNKPGQ' is an internal UPI merchant code (not a merchant name), '648600156354' is the REFERENCE_ID.\n");
        sb.append("    CORRECT: extract the 12-digit number (e.g. 648600156354) as REFERENCE_ID. Do NOT extract the full string as MERCHANT.\n");
        sb.append("These non-data fields add noise, cause template conflicts, and reduce matching accuracy.\n\n");
        sb.append("EXTRACTEDFIELDS RULES:\n");
        sb.append("- extractedFields must contain the raw extracted value only — no surrounding words.\n");
        sb.append("- The value must exactly match what the regex would capture from the normalized SMS (i.e. what matcher.group() returns — the lookbehind is not included in the match).\n");
        sb.append("- DUPLICATE ENTITY NAMES: when the same entity name appears more than once (e.g. two AMOUNT entities), JSON does not allow duplicate keys.\n");
        sb.append("  Use the semanticType as the key in extractedFields instead of the name.\n");
        sb.append("  Example: AMOUNT appears as TOTAL_DUE and MINIMUM_DUE → extractedFields: {\"TOTAL_DUE\": \"48795\", \"MINIMUM_DUE\": \"2969.66\", ...}\n");
        sb.append("  Example: DATE appears as START_DATE and END_DATE → extractedFields: {\"START_DATE\": \"19/05/2026\", \"END_DATE\": \"16/05/2036\", ...}\n");
        sb.append("- NEVER use null, \"null\", or empty string as a value in extractedFields. If you cannot extract a value, omit that entity from extractedFields entirely.\n\n");

        if (!existingEntities.isEmpty()) {
            sb.append("EXISTING GLOBAL ENTITIES FOR THIS SENDER:\n");
            sb.append("For each existing entity, ALL known regex variants and boundary pairs are listed below.\n");
            sb.append("RULES for existing entities:\n");
            sb.append("  1. REUSE the entity name — never create a new name for a concept that already exists.\n");
            sb.append("  2. REGEX — check if any listed regex variant already matches the value in THIS message.\n");
            sb.append("     - If YES → return that exact regex unchanged.\n");
            sb.append("     - If NO  → return a new regex variant that correctly matches this format. The system will append it.\n");
            sb.append("  3. BOUNDARY — check if any listed boundary pair (startAfter + endBefore) already fires in THIS message.\n");
            sb.append("     - If YES → return that exact startAfter and endBefore unchanged.\n");
            sb.append("     - If NO  → return new startAfter and endBefore that correctly locate the value in this message. The system will append it.\n");
            sb.append("  4. Regex and boundary are checked INDEPENDENTLY — it is valid to return a new regex AND a new boundary for the same entity.\n");
            sb.append("  5. NEVER return a regex or boundary that is identical to one already listed — it adds no value.\n\n");
            for (GlobalEntity entity : existingEntities) {
                if (entity.getType() == com.sms.extraction.domain.ExtractionRuleType.REGEX) {
                    sb.append("- name=").append(entity.getName()).append(", type=REGEX\n");
                    sb.append("  existing regex variants:\n");
                    if (entity.getRegexVariants().isEmpty()) {
                        sb.append("    (none)\n");
                    } else {
                        for (com.sms.extraction.domain.RegexVariant variant : entity.getRegexVariants()) {
                            sb.append("    regex=").append(variant.getRegex()).append("\n");
                        }
                    }
                } else {
                    sb.append("- name=").append(entity.getName()).append(", type=BOUNDARY_HINT\n");
                    sb.append("  existing boundary pairs:\n");
                    if (entity.getBoundaryPairs().isEmpty()) {
                        sb.append("    (none)\n");
                    } else {
                        for (com.sms.extraction.domain.BoundaryPair pair : entity.getBoundaryPairs()) {
                            sb.append("    startAfter=").append(pair.getStartAfter())
                                    .append(", endBefore=").append(pair.getEndBefore()).append("\n");
                        }
                    }
                }
            }
            sb.append("\n");
        }

        sb.append("RESPONSE FORMAT (strict JSON, no other text):\n");
        sb.append("{\n");
        sb.append("  \"category\": \"string\",\n");
        sb.append("  \"subcategory\": \"string\",\n");
        sb.append("  \"intent\": \"string\",\n");
        sb.append("  \"confidenceScore\": 0.95,\n");
        sb.append("  \"extractedFields\": {\"AMOUNT\": \"9134.00\", \"ACCOUNT_NUMBER\": \"XX1161\", \"DATE\": \"01-05-2026\", \"TIME\": \"02:02:57 PM\", \"MERCHANT\": \"EKART\", \"AVAILABLE_LIMIT\": \"10493.09\"},\n");
        sb.append("  \"canonicalTemplate\": \"INR {AMOUNT} SPENT ON INDUSIND CARD {ACCOUNT_NUMBER} ON {DATE} {TIME} AT {MERCHANT} AVL LMT: INR {AVAILABLE_LIMIT}.\",\n");
        sb.append("  \"entities\": [\n");
        sb.append("    {\"name\": \"AMOUNT\",          \"type\": \"REGEX\",        \"regex\": \"(?<=INR )[0-9]+(?:\\\\.[0-9]{1,2})?\",          \"group\": 0, \"startAfter\": \"INR\",     \"endBefore\": \"SPENT\",  \"maxTokens\": 0},\n");
        sb.append("    {\"name\": \"ACCOUNT_NUMBER\",  \"type\": \"REGEX\",        \"regex\": \"(?<=CARD )XX[0-9]{4}\",                        \"group\": 0, \"startAfter\": \"CARD\",    \"endBefore\": \"ON\",     \"maxTokens\": 0},\n");
        sb.append("    {\"name\": \"DATE\",            \"type\": \"REGEX\",        \"regex\": \"(?<=ON )[0-9]{2}-[0-9]{2}-[0-9]{4}\",          \"group\": 0, \"startAfter\": \"ON\",      \"endBefore\": \"\",       \"maxTokens\": 0},\n");
        sb.append("    {\"name\": \"TIME\",            \"type\": \"REGEX\",        \"regex\": \"(?<=PM )[0-9]{2}:[0-9]{2}:[0-9]{2} (?:AM|PM)|(?<=[0-9]{4} )[0-9]{2}:[0-9]{2}:[0-9]{2} (?:AM|PM)\", \"group\": 0, \"startAfter\": \"ON\",  \"endBefore\": \"AT\",  \"maxTokens\": 0},\n");
        sb.append("    {\"name\": \"MERCHANT\",        \"type\": \"BOUNDARY_HINT\", \"regex\": null,                                           \"group\": 0, \"startAfter\": \"AT\",      \"endBefore\": \"AVL\",    \"maxTokens\": 2},\n");
        sb.append("  // If MERCHANT is followed directly by DATE (no fixed word between them), use {DATE} as endBefore:\n");
        sb.append("  // {\"name\": \"MERCHANT\", \"type\": \"BOUNDARY_HINT\", \"regex\": null, \"group\": 0, \"startAfter\": \"@\", \"endBefore\": \"{DATE}\", \"maxTokens\": 3}\n");
        sb.append("    {\"name\": \"AVAILABLE_LIMIT\", \"type\": \"REGEX\",        \"regex\": \"(?<=LMT: INR )[0-9]+(?:\\\\.[0-9]{1,2})?\",    \"group\": 0, \"startAfter\": \"LMT: INR\",\"endBefore\": \".\",      \"maxTokens\": 0}\n");
        sb.append("  ],\n");
        sb.append("  \"ordering\": [\"AMOUNT\", \"ACCOUNT_NUMBER\", \"DATE\", \"TIME\", \"MERCHANT\", \"AVAILABLE_LIMIT\"]\n");
        sb.append("}\n\n");


        return sb.toString();
    }

    private String buildUserPrompt(String normalizedSms) {
        return "Extract structured data from this normalized SMS:\n\n" + normalizedSms;
    }

    private LlmResponse parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String content = root.path("choices").get(0).path("message").path("content").asText();
            JsonNode parsed = objectMapper.readTree(content);

            String category = parsed.path("category").asText(null);
            String subcategory = parsed.path("subcategory").asText(null);
            String intent = parsed.path("intent").asText(null);
            double confidenceScore = parsed.path("confidenceScore").asDouble(0.0);
            String canonicalTemplate = parsed.path("canonicalTemplate").asText(null);

            Map<String, String> extractedFields = new HashMap<>();
            JsonNode fieldsNode = parsed.path("extractedFields");
            if (fieldsNode.isObject()) {
                fieldsNode.fields().forEachRemaining(entry -> {
                    String value = entry.getValue().asText(null);
                    if (value != null && !value.equals("null") && !value.isBlank()) {
                        extractedFields.put(entry.getKey(), value);
                    }
                });
            }

            List<LlmEntityInfo> entities = new ArrayList<>();
            JsonNode entitiesNode = parsed.path("entities");
            if (entitiesNode.isArray()) {
                for (JsonNode entityNode : entitiesNode) {
                    String typeName = entityNode.path("type").asText("REGEX");
                    ExtractionRuleType type;
                    try {
                        type = ExtractionRuleType.valueOf(typeName);
                    } catch (IllegalArgumentException ex) {
                        type = ExtractionRuleType.REGEX;
                    }
                    String entityName = entityNode.path("name").asText(null);
                    String semanticType = entityNode.path("semanticType").asText(null);
                    if (semanticType == null || semanticType.isBlank()) semanticType = entityName;
                    entities.add(LlmEntityInfo.builder()
                            .name(entityName)
                            .semanticType(semanticType)
                            .type(type)
                            .regex(entityNode.path("regex").isNull() ? null : entityNode.path("regex").asText(null))
                            .group(entityNode.path("group").asInt(0))
                            .startAfter(entityNode.path("startAfter").asText(""))
                            .endBefore(entityNode.path("endBefore").asText(""))
                            .maxTokens(entityNode.path("maxTokens").asInt(3))
                            .build());
                }
            }

            List<String> ordering = new ArrayList<>();
            JsonNode orderingNode = parsed.path("ordering");
            if (orderingNode.isArray()) {
                for (JsonNode item : orderingNode) {
                    ordering.add(item.asText());
                }
            }

            JsonNode usage = root.path("usage");
            long inputTokens     = usage.path("prompt_tokens").asLong(0);
            long outputTokens    = usage.path("completion_tokens").asLong(0);
            long cachedTokens    = usage.path("prompt_tokens_details").path("cached_tokens").asLong(0);
            long reasoningTokens = usage.path("completion_tokens_details").path("reasoning_tokens").asLong(0);

            return LlmResponse.builder()
                    .category(category)
                    .subcategory(subcategory)
                    .intent(intent)
                    .confidenceScore(confidenceScore)
                    .extractedFields(extractedFields)
                    .canonicalTemplate(canonicalTemplate)
                    .entities(entities)
                    .ordering(ordering)
                    .inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .cachedTokens(cachedTokens)
                    .reasoningTokens(reasoningTokens)
                    .build();

        } catch (Exception e) {
            log.error("Error parsing LLM response: {}", responseBody, e);
            throw new RuntimeException("Failed to parse LLM response", e);
        }
    }
}
