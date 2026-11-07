package clob.marketdata;

/**
 * Delivers one subscriber's update. Called on the market data thread, in the cycle's rotated order;
 * the view is reused after the call returns, so implementations must copy or encode it immediately.
 */
@FunctionalInterface
public interface MarketDataSink {
    void publish(long subscriptionId, long boundary, BookView view);
}
