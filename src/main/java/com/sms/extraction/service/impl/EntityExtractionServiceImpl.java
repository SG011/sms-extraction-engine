package com.sms.extraction.service.impl;

import com.sms.extraction.domain.BoundaryPair;
import com.sms.extraction.domain.CandidateTemplate;
import com.sms.extraction.domain.ExtractedValue;
import com.sms.extraction.domain.ExtractionRuleType;
import com.sms.extraction.domain.GlobalEntity;
import com.sms.extraction.domain.RegexVariant;
import com.sms.extraction.service.EntityExtractionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


/**
 * Runs extraction rules (REGEX and BOUNDARY_HINT) against a normalized SMS.
 *
 * REGEX entities: run regex, capture value and position.
 * BOUNDARY_HINT entities: find startAfter, then endBefore; greedy fallback to maxTokens words.
 *   startAfter/endBefore may be a fixed string token OR an entity reference written as {ENTITY_NAME}.
 *   Entity references are resolved positionally from already-extracted entities.
 *   BOUNDARY_HINT entities are extracted iteratively so dependencies are resolved first.
 *
 * Ambiguous REGEX matches produce separate CandidateTemplate objects — one per combination.
 */
@Service
public class EntityExtractionServiceImpl implements EntityExtractionService {

    private static final Logger log = LoggerFactory.getLogger(EntityExtractionServiceImpl.class);

    static final String SOM = "SOM";
    static final String EOM = "EOM";

    private boolean isSom(String boundary) { return SOM.equalsIgnoreCase(boundary); }
    private boolean isEom(String boundary) { return EOM.equalsIgnoreCase(boundary); }

    @Override
    public List<CandidateTemplate> extract(String normalizedSms, List<GlobalEntity> entities) {
        if (normalizedSms == null || normalizedSms.isEmpty() || entities == null || entities.isEmpty()) {
            return new ArrayList<>();
        }

        // resolved: entity name → its first extracted value (used to resolve {ENTITY_NAME} boundaries)
        Map<String, ExtractedValue> resolved = new LinkedHashMap<>();
        // allOccurrences: entity name → ALL extracted values (for multi-occurrence entity-reference resolution
        // and for span/position claiming — every occurrence of every regex entity must be visible to the
        // claiming checks so that a boundary spanning over a second AMOUNT or QUANTITY is correctly rejected)
        Map<String, List<ExtractedValue>> allOccurrences = new LinkedHashMap<>();

        List<List<ExtractedValue>> regexAlternatives = new ArrayList<>();
        List<GlobalEntity> boundaryEntities = new ArrayList<>();

        // Deduplicate REGEX entities with identical regex sets — if two entities share all
        // the same regex variants, they're the same thing with different names (LLM naming inconsistency).
        // Keep only the first occurrence per unique regex fingerprint.
        Set<String> seenRegexFingerprints = new HashSet<>();
        List<GlobalEntity> deduplicatedEntities = new ArrayList<>();
        for (GlobalEntity entity : entities) {
            if (entity.getType() == ExtractionRuleType.REGEX) {
                String fingerprint = entity.getRegexVariants().stream()
                        .map(RegexVariant::getRegex)
                        .sorted()
                        .collect(Collectors.joining("|"));
                if (!fingerprint.isEmpty() && !seenRegexFingerprints.add(fingerprint)) {
                    log.info("Deduplicating REGEX entity={} — identical regex to a previously seen entity, skipping",
                            entity.getName());
                    continue;
                }
            }
            deduplicatedEntities.add(entity);
        }

        // Collect all REGEX matches across all entities, then group by position
        // Matches at the same position from different entities = alternatives (one slot)
        // Matches at different positions from same entity = co-occurrences (required slots)
        Map<Integer, List<ExtractedValue>> matchesByPosition = new LinkedHashMap<>();

        for (GlobalEntity entity : deduplicatedEntities) {
            if (entity.getType() == ExtractionRuleType.REGEX) {
                List<ExtractedValue> allMatches = new ArrayList<>();
                for (com.sms.extraction.domain.RegexVariant variant : entity.getRegexVariants()) {
                    log.info("Trying REGEX entity={} regex='{}'", entity.getName(), variant.getRegex());
                    List<ExtractedValue> matches = extractRegexVariant(normalizedSms, entity.getName(), variant.getRegex(), variant.getGroup());
                    if (!matches.isEmpty()) {
                        log.info("REGEX entity={} regex='{}' matched {} values", entity.getName(), variant.getRegex(), matches.size());
                        for (ExtractedValue m : matches) {
                            // Keep all matches including those at the same start position —
                            // different regex variants may capture different lengths at the same position
                            // (e.g. '07/MAY' vs '07/MAY/2026'). All become alternatives in the cartesian
                            // product so template matching can pick the one that satisfies boundary conditions.
                            boolean duplicate = allMatches.stream().anyMatch(e ->
                                    e.getStartPosition() == m.getStartPosition() && e.getValue().equals(m.getValue()));
                            if (!duplicate) allMatches.add(m);
                        }
                    }
                }
                if (!allMatches.isEmpty()) {
                    log.info("REGEX entity={} total matches={}", entity.getName(), allMatches.size());
                    for (ExtractedValue match : allMatches) {
                        matchesByPosition.computeIfAbsent(match.getStartPosition(), k -> new ArrayList<>()).add(match);
                    }
                    resolved.put(entity.getName(), allMatches.get(0));
                    allOccurrences.put(entity.getName(), new ArrayList<>(allMatches));
                } else {
                    log.info("REGEX entity={} matched nothing across {} variants", entity.getName(), entity.getRegexVariants().size());
                }
            } else if (entity.getType() == ExtractionRuleType.BOUNDARY_HINT) {
                boundaryEntities.add(entity);
            }
        }

        // Build regexAlternatives: each position becomes one slot
        // Single match at a position = required slot; multiple matches = alternatives slot
        for (List<ExtractedValue> matchesAtPos : matchesByPosition.values()) {
            regexAlternatives.add(matchesAtPos);
        }

        // Step 2: Iteratively extract BOUNDARY_HINT entities.
        // Each round, process entities whose {ENTITY_NAME} boundary dependencies are now resolved.
        // For each entity, every boundary pair that fires produces a separate extraction variant.
        // All variants become separate candidates — template matching picks the one whose hash matches.
        // If none match we go to LLM anyway, so there is no downside to trying all pairs.
        List<List<ExtractedValue>> boundaryEntityAlternatives = new ArrayList<>();
        List<GlobalEntity> remaining = new ArrayList<>(boundaryEntities);
        boolean progress = true;
        while (progress && !remaining.isEmpty()) {
            progress = false;
            Iterator<GlobalEntity> it = remaining.iterator();
            while (it.hasNext()) {
                GlobalEntity entity = it.next();
                if (!isPairReady(entity, resolved)) {
                    continue;
                }
                it.remove();
                progress = true;
                log.info("Trying BOUNDARY_HINT entity={} boundaryPairs={}", entity.getName(), entity.getBoundaryPairs());
                List<ExtractedValue> allRegexMatches = allOccurrences.values().stream()
                        .flatMap(List::stream).collect(Collectors.toList());
                List<List<ExtractedValue>> occurrenceGroups = extractBoundaryOccurrenceGroups(
                        normalizedSms, entity, resolved, allRegexMatches, allOccurrences);
                if (!occurrenceGroups.isEmpty()) {
                    log.info("BOUNDARY_HINT entity={} extracted {} occurrence(s): {}", entity.getName(),
                            occurrenceGroups.size(),
                            occurrenceGroups.stream().map(g -> g.get(0).getValue()).toList());
                    for (List<ExtractedValue> group : occurrenceGroups) {
                        boundaryEntityAlternatives.add(group);
                    }
                    resolved.put(entity.getName(), occurrenceGroups.get(0).get(0));
                } else {
                    log.info("BOUNDARY_HINT entity={} extracted nothing", entity.getName());
                }
            }
        }

        if (boundaryEntityAlternatives.isEmpty() && regexAlternatives.isEmpty()) {
            log.debug("No values extracted from normalized SMS");
            return new ArrayList<>();
        }

        // Cartesian product across entities: one value per entity, one per firing pair.
        // Each combination goes through overlap resolution to produce final boundary variants.
        List<List<ExtractedValue>> boundaryVariants = new ArrayList<>();
        if (!boundaryEntityAlternatives.isEmpty()) {
            for (List<ExtractedValue> combo : cartesianProduct(boundaryEntityAlternatives)) {
                boundaryVariants.addAll(generateNonOverlappingVariants(combo));
            }
        } else {
            boundaryVariants.add(Collections.emptyList());
        }
        log.info("Boundary variants after pair expansion: {}", boundaryVariants.size());

        // Power-set: for each boundary combination, generate every possible subset of its entities.
        // This ensures that any combination of bleed entities can be excluded while genuine
        // boundary entities are retained — e.g. drop CARD_NAME but keep MERCHANT, or drop both,
        // or keep all. Every subset is tried in template matching; first hash hit wins.
        if (!boundaryEntityAlternatives.isEmpty() && !boundaryVariants.isEmpty()) {
            Set<String> seen = new HashSet<>();
            for (List<ExtractedValue> v : boundaryVariants) seen.add(boundarySignature(v));
            List<List<ExtractedValue>> subsets = new ArrayList<>();
            for (List<ExtractedValue> variant : boundaryVariants) {
                int n = variant.size();
                // mask=0 → empty (regex-only fallback handles this)
                // mask=(1<<n)-1 → full set (already in boundaryVariants)
                // everything in between → all other subsets
                for (int mask = 1; mask < (1 << n) - 1; mask++) {
                    List<ExtractedValue> subset = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        if ((mask & (1 << i)) != 0) subset.add(variant.get(i));
                    }
                    if (seen.add(boundarySignature(subset))) subsets.add(subset);
                }
            }
            boundaryVariants = new ArrayList<>(boundaryVariants);
            boundaryVariants.addAll(subsets);
        }

        // Always add a regex-only variant as a fallback.
        // When a boundary entity bleeds into a message it doesn't belong in, all boundary
        // candidates include that entity and miss the stored template. The regex-only candidate
        // has no boundary entities and can still match templates that don't expect them.
        if (!boundaryEntityAlternatives.isEmpty()) {
            boundaryVariants = new ArrayList<>(boundaryVariants);
            boundaryVariants.add(Collections.emptyList());
        }

        List<List<ExtractedValue>> regexCombinations = cartesianProduct(regexAlternatives);

        List<CandidateTemplate> candidates = new ArrayList<>();

        for (List<ExtractedValue> boundaryVariant : boundaryVariants) {
            if (regexCombinations.isEmpty()) {
                CandidateTemplate candidate = buildCandidate(boundaryVariant, new ArrayList<>());
                if (!candidate.getExtractedValues().isEmpty()) {
                    candidates.add(candidate);
                }
            } else {
                for (List<ExtractedValue> regexCombo : regexCombinations) {
                    List<ExtractedValue> combined = new ArrayList<>(boundaryVariant);
                    combined.addAll(regexCombo);
                    CandidateTemplate candidate = buildCandidate(combined, new ArrayList<>());
                    if (!candidate.getExtractedValues().isEmpty()) {
                        candidates.add(candidate);
                    }
                }
            }
        }

        return candidates;
    }

    /**
     * Recursively generates all non-overlapping subsets of extracted values by resolving conflicts.
     * When two values overlap, splits into two variants: one without A, one without B.
     */
    private List<List<ExtractedValue>> generateNonOverlappingVariants(List<ExtractedValue> values) {
        List<ExtractedValue> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.comparingInt(ExtractedValue::getStartPosition));

        for (int i = 0; i < sorted.size() - 1; i++) {
            ExtractedValue a = sorted.get(i);
            ExtractedValue b = sorted.get(i + 1);
            if (a.getEndPosition() > b.getStartPosition()) {
                log.info("Boundary overlap detected: entity='{}' [{}-{}] overlaps entity='{}' [{}-{}] — creating two variants",
                        a.getEntityName(), a.getStartPosition(), a.getEndPosition(),
                        b.getEntityName(), b.getStartPosition(), b.getEndPosition());

                List<ExtractedValue> withoutA = new ArrayList<>(sorted);
                withoutA.remove(a);
                List<ExtractedValue> withoutB = new ArrayList<>(sorted);
                withoutB.remove(b);

                List<List<ExtractedValue>> result = new ArrayList<>();
                result.addAll(generateNonOverlappingVariants(withoutA));
                result.addAll(generateNonOverlappingVariants(withoutB));
                return result;
            }
        }

        List<List<ExtractedValue>> result = new ArrayList<>();
        result.add(sorted);
        return result;
    }

    /**
     * Returns true if at least one boundary pair has all its {ENTITY_NAME} dependencies resolved.
     * Fixed string boundaries and empty boundaries are always considered ready.
     */
    private boolean isPairReady(GlobalEntity entity, Map<String, ExtractedValue> resolved) {
        for (BoundaryPair pair : entity.getBoundaryPairs()) {
            boolean startReady = !isEntityRef(pair.getStartAfter()) || resolved.containsKey(resolveEntityName(pair.getStartAfter()));
            boolean endReady = !isEntityRef(pair.getEndBefore()) || resolved.containsKey(resolveEntityName(pair.getEndBefore()));
            if (startReady && endReady) return true;
        }
        return false;
    }

    /** Returns true if the boundary value is an entity reference written as {ENTITY_NAME}. */
    private boolean isPositionClaimed(int position, List<ExtractedValue> allRegexMatches) {
        for (ExtractedValue ev : allRegexMatches) {
            if (position >= ev.getStartPosition() && position < ev.getEndPosition()) {
                return true;
            }
        }
        return false;
    }

    private boolean isEntityRef(String boundary) {
        return boundary != null && boundary.length() > 2 && boundary.startsWith("{") && boundary.endsWith("}");
    }

    /** Strips the braces from {ENTITY_NAME} to get the entity name. */
    private String resolveEntityName(String boundary) {
        return boundary.substring(1, boundary.length() - 1);
    }

    private List<ExtractedValue> extractRegexVariant(String normalizedSms, String entityName, String regex, int group) {
        List<ExtractedValue> results = new ArrayList<>();
        if (regex == null || regex.isEmpty()) return results;
        try {
            Pattern pattern = Pattern.compile(regex);
            Matcher matcher = pattern.matcher(normalizedSms);
            while (matcher.find()) {
                String value = group > 0 ? matcher.group(group) : matcher.group();
                int start = group > 0 ? matcher.start(group) : matcher.start();
                int end = group > 0 ? matcher.end(group) : matcher.end();
                results.add(ExtractedValue.builder()
                        .entityName(entityName)
                        .value(value)
                        .startPosition(start)
                        .endPosition(end)
                        .build());
            }
        } catch (Exception e) {
            log.warn("Regex failed entity={} regex='{}'", entityName, regex, e);
        }
        return results;
    }


    private String boundarySignature(List<ExtractedValue> values) {
        return values.stream()
                .map(ev -> ev.getEntityName() + "=" + ev.getValue())
                .collect(Collectors.joining("|"));
    }

    /**
     * Extracts all occurrences of a BOUNDARY_HINT entity across the message.
     *
     * For pairs using a fixed startAfter token: tried once (standard behaviour).
     * For pairs using {ENTITY_NAME} as startAfter: tried once per occurrence of that
     * entity in the message. This handles repeating structures like SECURITY_NAME
     * that is anchored to each QUANTITY occurrence in CDSL debit messages.
     *
     * Returns a list of occurrence groups. Each group is one non-overlapping extraction
     * position with one or more alternative values. Multiple groups mean the entity
     * appears more than once in the message and all occurrences should be extracted.
     */
    private List<List<ExtractedValue>> extractBoundaryOccurrenceGroups(
            String normalizedSms, GlobalEntity entity,
            Map<String, ExtractedValue> resolved,
            List<ExtractedValue> allRegexMatches,
            Map<String, List<ExtractedValue>> allOccurrences) {

        List<BoundaryPair> sortedPairs = entity.getBoundaryPairs().stream()
                .sorted(Comparator.comparingInt(this::boundaryPairSpecificity).reversed())
                .toList();

        List<ExtractedValue> allExtractions = new ArrayList<>();
        Set<String> seenValues = new LinkedHashSet<>();

        for (BoundaryPair pair : sortedPairs) {
            String startAfter = pair.getStartAfter();
            if (isEntityRef(startAfter)) {
                // Try once per occurrence of the referenced entity
                String refName = resolveEntityName(startAfter);
                List<ExtractedValue> refs = allOccurrences.containsKey(refName)
                        ? allOccurrences.get(refName)
                        : (resolved.containsKey(refName) ? List.of(resolved.get(refName)) : Collections.emptyList());
                for (ExtractedValue ref : refs) {
                    Map<String, ExtractedValue> tempResolved = new HashMap<>(resolved);
                    tempResolved.put(refName, ref);
                    // tempResolved for entity-ref resolution, resolvedRegex for position-claim checks
                    ExtractedValue ev = tryExtractBoundary(normalizedSms, entity.getName(), pair, tempResolved, allRegexMatches, 0);
                    if (ev != null && seenValues.add(ev.getValue())) {
                        allExtractions.add(ev);
                    }
                }
            } else {
                int fromIdx = 0;
                while (true) {
                    ExtractedValue ev = tryExtractBoundary(normalizedSms, entity.getName(), pair, resolved, allRegexMatches, fromIdx);
                    if (ev == null) break;
                    if (seenValues.add(ev.getValue())) {
                        allExtractions.add(ev);
                    }
                    fromIdx = ev.getEndPosition();
                }
            }
        }

        if (allExtractions.isEmpty()) return Collections.emptyList();

        // Deduplicate by start position: keep only the shortest extraction at each start.
        // A long extraction (e.g. from a broad endBefore='ON') can span over the positions
        // of later occurrences, which would incorrectly merge them into the same group.
        // Keeping the shortest at each start ensures each distinct position is its own occurrence.
        Map<Integer, ExtractedValue> byStartPos = new LinkedHashMap<>();
        for (ExtractedValue ev : allExtractions) {
            byStartPos.merge(ev.getStartPosition(), ev,
                    (existing, candidate) -> candidate.getEndPosition() < existing.getEndPosition() ? candidate : existing);
        }
        List<ExtractedValue> deduped = new ArrayList<>(byStartPos.values());
        deduped.sort(Comparator.comparingInt(ExtractedValue::getStartPosition));

        // Group non-overlapping positions → each group is one occurrence of the entity
        List<List<ExtractedValue>> groups = new ArrayList<>();
        List<ExtractedValue> currentGroup = new ArrayList<>();
        int maxEnd = -1;
        for (ExtractedValue ev : deduped) {
            if (maxEnd < 0 || ev.getStartPosition() >= maxEnd) {
                if (!currentGroup.isEmpty()) groups.add(currentGroup);
                currentGroup = new ArrayList<>();
                maxEnd = ev.getEndPosition();
            } else {
                maxEnd = Math.max(maxEnd, ev.getEndPosition());
            }
            currentGroup.add(ev);
        }
        if (!currentGroup.isEmpty()) groups.add(currentGroup);

        return groups;
    }

    private int boundaryPairSpecificity(BoundaryPair pair) {
        int sa = pair.getStartAfter() != null ? pair.getStartAfter().length() : 0;
        String eb = pair.getEndBefore() != null ? pair.getEndBefore() : "";
        // EOM and empty endBefore are unbounded — don't count toward specificity
        int ebScore = (eb.isEmpty() || isEom(eb)) ? 0 : eb.length();
        return sa + ebScore;
    }

    /**
     * Tries to extract using the pair's startAfter and endBefore.
     * If startAfter/endBefore is an entity name, uses that entity's extracted position.
     * If it is an unresolved entity reference, skips this pair (returns null).
     */
    private ExtractedValue tryExtractBoundary(String normalizedSms, String entityName, BoundaryPair pair,
                                               Map<String, ExtractedValue> resolved,
                                               List<ExtractedValue> allRegexMatches,
                                               int startSearchFrom) {
        String startAfter = pair.getStartAfter();
        if (startAfter == null || startAfter.isEmpty()) return null;

        String endBefore = pair.getEndBefore();
        boolean greedyMode = (endBefore == null || endBefore.isEmpty());

        // Skip pair if an entity-reference dependency is not yet resolved
        if (isEntityRef(startAfter) && !resolved.containsKey(resolveEntityName(startAfter))) return null;
        if (!greedyMode && isEntityRef(endBefore) && !resolved.containsKey(resolveEntityName(endBefore))) return null;

        int valueStart;
        if (isSom(startAfter)) {
            if (startSearchFrom > 0) return null; // SOM fires at most once
            valueStart = 0;
        } else if (isEntityRef(startAfter)) {
            valueStart = resolved.get(resolveEntityName(startAfter)).getEndPosition();
            while (valueStart < normalizedSms.length() && !Character.isLetterOrDigit(normalizedSms.charAt(valueStart))) {
                valueStart++;
            }
        } else {
            int startAfterIdx = findTokenInText(normalizedSms, startAfter, startSearchFrom);
            if (startAfterIdx < 0) return null;
            valueStart = startAfterIdx + startAfter.length();
            if (valueStart < normalizedSms.length() && normalizedSms.charAt(valueStart) == ' ') valueStart++;
            // Skip occurrences where valueStart falls inside an already-claimed REGEX position
            while (isPositionClaimed(valueStart, allRegexMatches)) {
                log.info("startAfter='{}' at idx={} leads to claimed position={}, trying next occurrence",
                        startAfter, startAfterIdx, valueStart);
                startAfterIdx = findTokenInText(normalizedSms, startAfter, startAfterIdx + 1);
                if (startAfterIdx < 0) return null;
                valueStart = startAfterIdx + startAfter.length();
                if (valueStart < normalizedSms.length() && normalizedSms.charAt(valueStart) == ' ') valueStart++;
            }
        }

        int endBeforeIdx;
        if (isEom(endBefore)) {
            endBeforeIdx = normalizedSms.length();
        } else if (greedyMode) {
            endBeforeIdx = greedyEndIndex(normalizedSms, valueStart, pair.getMaxTokens());
            if (endBeforeIdx < 0) return null;
        } else if (isEntityRef(endBefore)) {
            endBeforeIdx = resolved.get(resolveEntityName(endBefore)).getStartPosition();
        } else {
            endBeforeIdx = findTokenInText(normalizedSms, endBefore, valueStart);
            if (endBeforeIdx < 0) return null;
        }

        // If any claimed regex position falls within the extraction span [valueStart, endBeforeIdx],
        // the boundary is spanning over a regex entity — skip this startAfter occurrence and try next.
        // SOM has no alternative occurrence to retry — skip this check entirely for SOM.
        // Entity-ref startAfter is positionally fixed (anchored to a specific entity occurrence) so
        // there is no "next occurrence" to jump to — return null and let the caller's loop try the
        // next occurrence of the referenced entity instead.
        if (!isSom(startAfter) && isSpanClaimedByRegex(valueStart, endBeforeIdx, allRegexMatches)) {
            if (isEntityRef(startAfter)) {
                log.info("Span-claimed: entity-ref startAfter='{}' span [{}-{}] contains claimed regex position — skipping for entity='{}'",
                        startAfter, valueStart, endBeforeIdx, entityName);
                return null;
            }
            int startAfterIdx = findTokenInText(normalizedSms, startAfter, 0);
            // Find the occurrence we used and try from the next one
            while (startAfterIdx >= 0) {
                int vs = startAfterIdx + startAfter.length();
                if (vs < normalizedSms.length() && normalizedSms.charAt(vs) == ' ') vs++;
                if (vs == valueStart) break;
                startAfterIdx = findTokenInText(normalizedSms, startAfter, startAfterIdx + 1);
            }
            if (startAfterIdx >= 0) {
                startAfterIdx = findTokenInText(normalizedSms, startAfter, startAfterIdx + 1);
                while (startAfterIdx >= 0) {
                    int vs = startAfterIdx + startAfter.length();
                    if (vs < normalizedSms.length() && normalizedSms.charAt(vs) == ' ') vs++;
                    if (!isPositionClaimed(vs, allRegexMatches)) {
                        int eb = findTokenInText(normalizedSms, endBefore, vs);
                        if (eb > 0 && !isSpanClaimedByRegex(vs, eb, allRegexMatches)) {
                            valueStart = vs;
                            endBeforeIdx = eb;
                            break;
                        }
                    }
                    startAfterIdx = findTokenInText(normalizedSms, startAfter, startAfterIdx + 1);
                }
                if (startAfterIdx < 0) {
                    log.info("Span-claimed: all occurrences of startAfter='{}' lead to spans containing claimed regex positions for entity='{}'",
                            startAfter, entityName);
                    return null;
                }
            } else {
                log.info("Span-claimed: startAfter='{}' span [{}-{}] contains claimed regex position — no more occurrences for entity='{}'",
                        startAfter, valueStart, endBeforeIdx, entityName);
                return null;
            }
        }

        int valueEnd = endBeforeIdx;
        if (valueEnd > valueStart && normalizedSms.charAt(valueEnd - 1) == ' ') {
            valueEnd--;
        }
        if (valueEnd <= valueStart) return null;

        String extracted = normalizedSms.substring(valueStart, valueEnd).trim();
        if (extracted.isEmpty()) return null;

        return ExtractedValue.builder()
                .entityName(entityName)
                .value(extracted)
                .startPosition(valueStart)
                .endPosition(valueEnd)
                .build();
    }

    private int greedyEndIndex(String text, int valueStart, int maxTokens) {
        if (maxTokens <= 0 || valueStart >= text.length()) return -1;
        int wordCount = 0;
        int i = valueStart;
        while (i < text.length() && wordCount < maxTokens) {
            // skip spaces between words
            while (i < text.length() && text.charAt(i) == ' ') i++;
            if (i >= text.length()) break;
            // consume a word (non-space chars)
            while (i < text.length() && text.charAt(i) != ' ') i++;
            wordCount++;
        }
        return wordCount > 0 ? i : -1;
    }

    private boolean isSpanClaimedByRegex(int spanStart, int spanEnd, List<ExtractedValue> allRegexMatches) {
        for (ExtractedValue ev : allRegexMatches) {
            if (ev.getStartPosition() < spanEnd && ev.getEndPosition() > spanStart) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds a token in text using word-boundary semantics.
     * Punctuation-only tokens use plain contains(). For ".", skips occurrences between digits.
     */
    private int findTokenInText(String text, String token, int fromIndex) {
        String upperToken = token.toUpperCase();

        if (upperToken.chars().noneMatch(Character::isLetterOrDigit)) {
            int idx = text.indexOf(upperToken, fromIndex);
            while (idx >= 0) {
                int endIdx = idx + upperToken.length();
                boolean prevDigit = idx > 0 && Character.isDigit(text.charAt(idx - 1));
                boolean nextDigit = endIdx < text.length() && Character.isDigit(text.charAt(endIdx));
                if (!(prevDigit && nextDigit)) {
                    return idx;
                }
                idx = text.indexOf(upperToken, idx + 1);
            }
            return -1;
        }

        int idx = text.indexOf(upperToken, fromIndex);
        while (idx >= 0) {
            boolean startOk = !Character.isLetterOrDigit(upperToken.charAt(0)) ||
                              (idx == 0) || !Character.isLetterOrDigit(text.charAt(idx - 1));
            int endIdx = idx + upperToken.length();
            boolean endOk = !Character.isLetterOrDigit(upperToken.charAt(upperToken.length() - 1)) ||
                            (endIdx >= text.length()) || !Character.isLetter(text.charAt(endIdx));
            if (startOk && endOk) {
                return idx;
            }
            idx = text.indexOf(upperToken, idx + 1);
        }
        return -1;
    }

    /**
     * Builds a CandidateTemplate from extracted values sorted by position.
     * Returns an empty candidate if any two spans overlap.
     */
    private CandidateTemplate buildCandidate(List<ExtractedValue> extracted, List<String> extraNames) {
        List<ExtractedValue> sorted = new ArrayList<>(extracted);
        sorted.sort(Comparator.comparingInt(ExtractedValue::getStartPosition));

        for (int i = 0; i < sorted.size() - 1; i++) {
            ExtractedValue a = sorted.get(i);
            ExtractedValue b = sorted.get(i + 1);
            if (a.getEndPosition() > b.getStartPosition()) {
                log.info("CANDIDATE_DISCARDED_OVERLAP entity='{}' [{}-{}] overlaps entity='{}' [{}-{}]",
                        a.getEntityName(), a.getStartPosition(), a.getEndPosition(),
                        b.getEntityName(), b.getStartPosition(), b.getEndPosition());
                return CandidateTemplate.builder()
                        .extractedValues(new ArrayList<>())
                        .entityNamesInOrder(new ArrayList<>())
                        .build();
            }
        }

        List<String> namesInOrder = new ArrayList<>();
        for (ExtractedValue ev : sorted) {
            namesInOrder.add(ev.getEntityName());
        }

        return CandidateTemplate.builder()
                .extractedValues(sorted)
                .entityNamesInOrder(namesInOrder)
                .build();
    }

    private List<List<ExtractedValue>> cartesianProduct(List<List<ExtractedValue>> lists) {
        List<List<ExtractedValue>> result = new ArrayList<>();
        result.add(new ArrayList<>());

        for (List<ExtractedValue> alternatives : lists) {
            List<List<ExtractedValue>> newResult = new ArrayList<>();
            for (List<ExtractedValue> existing : result) {
                for (ExtractedValue alternative : alternatives) {
                    List<ExtractedValue> newCombo = new ArrayList<>(existing);
                    newCombo.add(alternative);
                    newResult.add(newCombo);
                }
            }
            result = newResult;
        }

        return result;
    }
}
