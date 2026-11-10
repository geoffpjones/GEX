package clob.credit;

/** Why a credit or reference-data input was refused. A refused input changes no state, live or in replay. */
public enum CreditRefusal {
    NULL_FIELD,
    UNKNOWN_ACCOUNT,
    INVALID_LINE_ID,
    UNKNOWN_LINE,
    LINE_OWNED_BY_ANOTHER_ENTITY,
    LINE_TYPE_CHANGE_WITH_OPEN_USAGE,
    INVALID_LIMIT,
    UNKNOWN_INSTRUMENT,
    UNKNOWN_CURRENCY,
    INVALID_RATE,
    INVALID_VALUE_DATES,
    VALUE_DATE_BEFORE_BUSINESS_DATE,
    BUSINESS_DATE_WENT_BACKWARDS
}
