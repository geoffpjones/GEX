package clob.credit;

/**
 * Type of a credit line. "No credit" is the absence of a line ({@link CreditEngine#NO_LINE}).
 */
public enum CreditType {
    /** No limit; usage is not tracked. */
    UNLIMITED,
    /** Daily settlement limit: gross USD settling on each value date. */
    DSL,
    /** Net open position limit: |signed USD position| across all future value dates. */
    NOP
}
