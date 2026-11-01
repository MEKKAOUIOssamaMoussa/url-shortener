package dev.mekkaoui.shortener.analytics;

/**
 * Interface for recording click events on short URLs.
 */
public interface ClickRecorder {

    /**
     * Records a click on the specified short code.
     *
     * Contract:
     * - Never blocks on I/O.
     * - O(1) in-memory operation.
     * - Never throws any exception.
     *
     * @param code the short URL code that was redirected
     */
    void record(String code);
}
