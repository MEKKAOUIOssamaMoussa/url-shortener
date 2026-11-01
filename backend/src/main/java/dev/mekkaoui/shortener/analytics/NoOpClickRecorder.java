package dev.mekkaoui.shortener.analytics;

/**
 * No-op implementation of {@link ClickRecorder} used when analytics is disabled.
 * Performs no operations, creates no threads, and issues no writes.
 */
public class NoOpClickRecorder implements ClickRecorder {

    @Override
    public void record(String code) {
        // no-op
    }
}
