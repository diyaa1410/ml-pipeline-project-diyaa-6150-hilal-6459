package com.mlpipeline.benchmark;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Sequential vs. parallel benchmark for one CPU-bound operation, as
 * required by the assignment ("Sequential vs parallel benchmark for one
 * CPU-bound operation").
 *
 * The operation: word-frequency counting across a large synthetic corpus
 * of documents - a stand-in for the kind of feature-extraction /
 * pre-processing step a real text-classification pipeline would run
 * before inference. It's genuinely CPU-bound (string splitting + map
 * updates, no I/O), so it demonstrates a real speedup from parallelism.
 *
 * Run with:
 *   java -cp out com.mlpipeline.benchmark.CpuBoundBenchmark
 */
public class CpuBoundBenchmark {

    public static void main(String[] args) {
        int numDocs = 20_000;
        int wordsPerDoc = 200;
        List<String> corpus = generateCorpus(numDocs, wordsPerDoc);

        System.out.println("=== CPU-bound benchmark: word frequency over " + numDocs + " documents ===");

        long t0 = System.nanoTime();
        Map<String, Long> sequentialResult = countSequential(corpus);
        long t1 = System.nanoTime();
        double sequentialMs = (t1 - t0) / 1_000_000.0;
        System.out.printf("Sequential : %.1f ms  (distinct words: %d)%n", sequentialMs, sequentialResult.size());

        int cores = Runtime.getRuntime().availableProcessors();
        long t2 = System.nanoTime();
        Map<String, Long> parallelResult = countParallel(corpus, cores);
        long t3 = System.nanoTime();
        double parallelMs = (t3 - t2) / 1_000_000.0;
        System.out.printf("Parallel (%d threads) : %.1f ms  (distinct words: %d)%n", cores, parallelMs, parallelResult.size());

        System.out.printf("Speedup : %.2fx%n", sequentialMs / parallelMs);
        System.out.println();
        System.out.println("Correctness check: " + (sequentialResult.equals(parallelResult) ? "PASS - identical word counts" : "FAIL - results differ!"));
    }

    static List<String> generateCorpus(int numDocs, int wordsPerDoc) {
        String[] vocab = {"good", "bad", "great", "terrible", "product", "service", "amazing",
                "awful", "recommend", "quality", "support", "team", "price", "value", "fast",
                "slow", "easy", "hard", "love", "hate", "the", "and", "of", "system", "network"};
        Random rnd = new Random(42);
        return IntStream.range(0, numDocs)
                .mapToObj(i -> IntStream.range(0, wordsPerDoc)
                        .mapToObj(j -> vocab[rnd.nextInt(vocab.length)])
                        .collect(Collectors.joining(" ")))
                .collect(Collectors.toList());
    }

    static Map<String, Long> countSequential(List<String> corpus) {
        Map<String, Long> counts = new java.util.HashMap<>();
        for (String doc : corpus) {
            for (String word : doc.split(" ")) {
                counts.merge(word, 1L, Long::sum);
            }
        }
        return counts;
    }

    static Map<String, Long> countParallel(List<String> corpus, int threads) {
        Map<String, Long> counts = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        int chunkSize = (int) Math.ceil(corpus.size() / (double) threads);
        List<Future<?>> futures = new java.util.ArrayList<>();

        for (int t = 0; t < threads; t++) {
            int start = t * chunkSize;
            int end = Math.min(corpus.size(), start + chunkSize);
            if (start >= end) continue;
            futures.add(pool.submit(() -> {
                // Each thread accumulates into a local map first to avoid
                // hammering the shared ConcurrentHashMap for every single word,
                // then merges once at the end - far less contention.
                Map<String, Long> local = new java.util.HashMap<>();
                for (int i = start; i < end; i++) {
                    for (String word : corpus.get(i).split(" ")) {
                        local.merge(word, 1L, Long::sum);
                    }
                }
                local.forEach((k, v) -> counts.merge(k, v, Long::sum));
            }));
        }

        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        pool.shutdown();
        return counts;
    }
}
