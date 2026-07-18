package com.mlpipeline.model;

import com.mlpipeline.util.SimpleJson;

/**
 * Immutable snapshot of a job's state. Rather than mutating a shared object
 * from multiple pipeline stages (which would need locking to be visible
 * correctly across threads), each stage builds a brand-new JobResult and
 * publishes it into the ConcurrentHashMap in Main. This is the "immutable
 * snapshot" safe-publication pattern from Week 4: readers on GET /status
 * always see a fully-formed, consistent object, never a half-updated one.
 */
public final class JobResult {

    public enum Status { QUEUED, PROCESSING, COMPLETED, FAILED, REJECTED }

    public final String jobId;
    public final Status status;
    public final String label;
    public final double confidence;
    public final boolean fallbackUsed;
    public final String error;
    public final long latencyMs;

    private JobResult(String jobId, Status status, String label, double confidence,
                       boolean fallbackUsed, String error, long latencyMs) {
        this.jobId = jobId;
        this.status = status;
        this.label = label;
        this.confidence = confidence;
        this.fallbackUsed = fallbackUsed;
        this.error = error;
        this.latencyMs = latencyMs;
    }

    public static JobResult queued(String jobId) {
        return new JobResult(jobId, Status.QUEUED, null, 0, false, null, 0);
    }

    public static JobResult processing(String jobId) {
        return new JobResult(jobId, Status.PROCESSING, null, 0, false, null, 0);
    }

    public static JobResult completed(String jobId, String label, double confidence, boolean fallbackUsed, long latencyMs) {
        return new JobResult(jobId, Status.COMPLETED, label, confidence, fallbackUsed, null, latencyMs);
    }

    public static JobResult failed(String jobId, String error, long latencyMs) {
        return new JobResult(jobId, Status.FAILED, null, 0, false, error, latencyMs);
    }

    public static JobResult rejected(String jobId, String error) {
        return new JobResult(jobId, Status.REJECTED, null, 0, false, error, 0);
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"jobId\":\"").append(SimpleJson.escape(jobId)).append("\",");
        sb.append("\"status\":\"").append(status.name()).append("\",");
        sb.append("\"label\":").append(label == null ? "null" : "\"" + SimpleJson.escape(label) + "\"").append(",");
        sb.append("\"confidence\":").append(confidence).append(",");
        sb.append("\"fallbackUsed\":").append(fallbackUsed).append(",");
        sb.append("\"error\":").append(error == null ? "null" : "\"" + SimpleJson.escape(error) + "\"").append(",");
        sb.append("\"latencyMs\":").append(latencyMs);
        sb.append("}");
        return sb.toString();
    }
}
