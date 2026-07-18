package com.mlpipeline.model;

import java.time.Instant;

/**
 * Immutable representation of one incoming classification request.
 * Immutability gives us safe publication across threads for free (Week 4):
 * once constructed, a Job can be handed to any worker thread without
 * additional synchronization.
 */
public final class Job {
    public final String id;
    public final String idempotencyKey;
    public final String text;
    public final Instant receivedAt;

    public Job(String id, String idempotencyKey, String text, Instant receivedAt) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.text = text;
        this.receivedAt = receivedAt;
    }
}
