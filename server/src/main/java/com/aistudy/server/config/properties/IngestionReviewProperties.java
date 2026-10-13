package com.aistudy.server.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Review thresholds for the automatic ingestion issues (audit F-06).
 *
 * <p>A page order or outline confidence below its threshold is a signal for a
 * human reviewer, not a hard failure, so the issues these thresholds produce are
 * {@code MEDIUM}: they must be resolved or ignored before a source is worth
 * trusting, but they do not block publication the way a
 * {@code HIGH} {@code EXTRACTION_PARTIAL_FAILURE} does.
 *
 * <p>{@code maxIssuesPerRevision} bounds the page-level rows. Re-ingesting a
 * crookedly scanned book would otherwise write one issue per page, which neither
 * a review screen nor the blocker count can use.
 */
@ConfigurationProperties(prefix = "aistudy.ingestion.review")
public class IngestionReviewProperties {

    private double orderConfidenceThreshold = 0.7;

    private double outlineConfidenceThreshold = 0.6;

    private int maxIssuesPerRevision = 50;

    public double getOrderConfidenceThreshold() {
        return orderConfidenceThreshold;
    }

    public void setOrderConfidenceThreshold(double orderConfidenceThreshold) {
        this.orderConfidenceThreshold = orderConfidenceThreshold;
    }

    public double getOutlineConfidenceThreshold() {
        return outlineConfidenceThreshold;
    }

    public void setOutlineConfidenceThreshold(double outlineConfidenceThreshold) {
        this.outlineConfidenceThreshold = outlineConfidenceThreshold;
    }

    public int getMaxIssuesPerRevision() {
        return maxIssuesPerRevision;
    }

    public void setMaxIssuesPerRevision(int maxIssuesPerRevision) {
        this.maxIssuesPerRevision = maxIssuesPerRevision;
    }
}
