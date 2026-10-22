package com.tradingbot.strategy.breakoutvwap.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The BVS selection result: symbols per bucket, deduplicated across buckets in bucket-enum order
 * (GAINERS first, SQUEEZE last), shortfalls backfilled from the next rank of the same bucket.
 *
 * @param byBucket selected symbols per bucket; buckets may be absent or empty when degraded
 */
public record BvsUniverse(Map<BvsBucket, List<String>> byBucket) {

    public BvsUniverse {
        byBucket = byBucket == null ? Map.of() : Map.copyOf(byBucket);
    }

    /** Empty universe — the strategy stands down for the day. */
    public static BvsUniverse empty() {
        return new BvsUniverse(Map.of());
    }

    /**
     * Deduped union of every bucket, in bucket-enum order.
     *
     * @return ordered distinct symbols across all buckets, never null
     */
    public List<String> allSymbols() {
        LinkedHashSet<String> union = new LinkedHashSet<>();
        for (BvsBucket bucket : BvsBucket.values()) {
            union.addAll(byBucket.getOrDefault(bucket, List.of()));
        }
        return List.copyOf(union);
    }
}
