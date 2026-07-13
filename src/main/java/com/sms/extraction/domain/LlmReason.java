package com.sms.extraction.domain;

public enum LlmReason {

    // Entity extraction returned zero candidates — global entity list for this sender is empty (first time seeing sender)
    NO_EXTRACTION_CANDIDATES,

    // Entity list exists for this sender but extraction produced no valid candidates — overlap or regex mismatch bug
    EXTRACTION_CANDIDATES_DISCARDED,

    // Entities were extracted but both static text hash and placeholder sequence hash lookups missed
    NO_TEMPLATE_MATCH_BOTH_HASHES_MISSED,

    // Sequence hash matched one or more templates but boundary validation (startAfter/endBefore) failed for all of them
    BOUNDARY_VALIDATION_FAILED,

    // Multiple templates passed boundary validation but disagree on category/subcategory/intent — cannot pick one
    AMBIGUOUS_TEMPLATE_CONFLICT,

    // Template was found by hash lookup but is inactive (confidence score was below threshold when it was learned)
    LOW_CONFIDENCE_TEMPLATE_INACTIVE
}
