package frontend.ctrl;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import frontend.data.Sms;

@Controller
@RequestMapping(path = "/sms")
public class FrontendController {

    private String modelHost;
    private RestTemplateBuilder rest;
    private boolean cacheEnabled;
    private ConcurrentHashMap<String, String> predictionCache;

    //Monitor Objects
    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final String appVersion;

    //Counter activeRequests
    private final ConcurrentHashMap<String, AtomicLong> requestCounters = new ConcurrentHashMap<>();

    //Latency (Histogram with buckets)
    private final ConcurrentHashMap<String, DoubleAdder> latencySumMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> latencyCountMap = new ConcurrentHashMap<>();
    private final double[] latencyBuckets = {0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0};
    private final ConcurrentHashMap<String, ConcurrentHashMap<Double, AtomicLong>> latencyBucketCounts = new ConcurrentHashMap<>();

    //Message Length
    private final DoubleAdder msgLengthSum = new DoubleAdder();
    private final AtomicLong msgLengthCount = new AtomicLong(0);

    //UI Metrics
    private final AtomicLong pageViews = new AtomicLong(0);

    public FrontendController(RestTemplateBuilder rest, Environment env) {
        this.rest = rest;
        this.modelHost = env.getProperty("MODEL_HOST");
        this.cacheEnabled = Boolean.parseBoolean(env.getProperty("ENABLE_CACHE", "false"));
        this.predictionCache = new ConcurrentHashMap<>();
        this.appVersion = env.getProperty("APP_VERSION", "stable");

        assertModelHost();
        System.out.printf("Cache enabled: %s\n", cacheEnabled);
        System.out.printf("App version: %s\n", appVersion);
    }

    @GetMapping(value = "/metrics", produces = "text/plain; version=0.0.4; charset=utf-8")
    @ResponseBody
    public String getMetrics() {
        StringBuilder sb = new StringBuilder();

        // 1. Active Requests
        sb.append("# HELP app_sms_active_requests Number of requests currently being processed\n");
        sb.append("# TYPE app_sms_active_requests gauge\n");
        sb.append(String.format("app_sms_active_requests{version=\"%s\"} %d\n\n", appVersion, activeRequests.get()));

        // 2. Cache Size
        sb.append("# HELP app_cache_size Current number of entries in the cache\n");
        sb.append("# TYPE app_cache_size gauge\n");
        sb.append(String.format("app_cache_size{version=\"%s\"} %d\n\n", appVersion, predictionCache.size()));

        // 3. Requests Total
        sb.append("# HELP app_sms_requests_total Total number of SMS requests\n");
        sb.append("# TYPE app_sms_requests_total counter\n");
        requestCounters.forEach((labelKey, count) -> {
            // labelKey: result="spam",cache_status="miss"
            sb.append(String.format("app_sms_requests_total{version=\"%s\",%s} %d\n", appVersion, labelKey, count.get()));
        });
        sb.append("\n");

        // 4. Latency
        sb.append("# HELP app_sms_latency_seconds Time taken to process SMS prediction\n");
        sb.append("# TYPE app_sms_latency_seconds histogram\n");
        latencySumMap.forEach((labelKey, sum) -> {
            // Output bucket counts (cumulative)
            ConcurrentHashMap<Double, AtomicLong> buckets = latencyBucketCounts.getOrDefault(labelKey, new ConcurrentHashMap<>());
            long cumulative = 0;
            for (double bucket : latencyBuckets) {
                cumulative += buckets.getOrDefault(bucket, new AtomicLong(0)).get();
                sb.append(String.format("app_sms_latency_seconds_bucket{version=\"%s\",%s,le=\"%s\"} %d\n",
                    appVersion, labelKey, formatBucket(bucket), cumulative));
            }
            // +Inf bucket (equals total count)
            long count = latencyCountMap.getOrDefault(labelKey, new AtomicLong(0)).get();
            sb.append(String.format("app_sms_latency_seconds_bucket{version=\"%s\",%s,le=\"+Inf\"} %d\n",
                appVersion, labelKey, count));
            // Sum and count
            sb.append(String.format("app_sms_latency_seconds_sum{version=\"%s\",%s} %f\n", appVersion, labelKey, sum.sum()));
            sb.append(String.format("app_sms_latency_seconds_count{version=\"%s\",%s} %d\n", appVersion, labelKey, count));
        });
        sb.append("\n");

        // 5. Message Length
        sb.append("# HELP app_sms_message_length Distribution of SMS message lengths\n");
        sb.append("# TYPE app_sms_message_length summary\n");
        sb.append(String.format("app_sms_message_length_sum{version=\"%s\"} %f\n", appVersion, msgLengthSum.sum()));
        sb.append(String.format("app_sms_message_length_count{version=\"%s\"} %d\n\n", appVersion, msgLengthCount.get()));

        // 6. UI Page Views (Counter)
        sb.append("# HELP app_ui_page_views_total Total number of UI page views\n");
        sb.append("# TYPE app_ui_page_views_total counter\n");
        sb.append(String.format("app_ui_page_views_total{version=\"%s\"} %d\n\n", appVersion, pageViews.get()));

        return sb.toString();
    }

    private String formatBucket(double bucket) {
        if (bucket == (long) bucket) {
            return String.valueOf((long) bucket);
        }
        return String.valueOf(bucket);
    }

    private void assertModelHost() {
        if (modelHost == null || modelHost.strip().isEmpty()) {
            System.err.println("ERROR: ENV variable MODEL_HOST is null or empty");
            System.exit(1);
        }
        modelHost = modelHost.strip();
        if (modelHost.indexOf("://") == -1) {
            var m = "ERROR: ENV variable MODEL_HOST is missing protocol, like \"http://...\" (was: \"%s\")\n";
            System.err.printf(m, modelHost);
            System.exit(1);
        } else {
            System.out.printf("Working with MODEL_HOST=\"%s\"\n", modelHost);
        }
    }

    @PostMapping("/pageview")
    @ResponseBody
    public String trackPageView() {
        pageViews.incrementAndGet();
        return "ok";
    }

    @PostMapping({ "", "/" })
    @ResponseBody
    public Sms predict(@RequestBody Sms sms) {
        activeRequests.incrementAndGet();
        long startTime = System.nanoTime();

        System.out.printf("Requesting prediction for \"%s\" ...\n", sms.sms);

        String cacheStatus = "disabled";
        String result = "unknown";

        try {
            if (cacheEnabled && predictionCache.containsKey(sms.sms)) {
                sms.result = predictionCache.get(sms.sms);
                cacheStatus = "hit";
                System.out.printf("Cache HIT: %s\n", sms.result);
            } else {
                if (cacheEnabled) {
                    cacheStatus = "miss";
                    System.out.println("Cache MISS - calling model service");
                }
                sms.result = getPrediction(sms);

                if (cacheEnabled) {
                    predictionCache.put(sms.sms, sms.result);
                }
            }

            result = sms.result != null ? sms.result.toLowerCase() : "unknown";
            System.out.printf("Prediction: %s\n", sms.result);
            return sms;

        } finally {
            activeRequests.decrementAndGet();
            long durationNanos = System.nanoTime() - startTime;
            double durationSeconds = durationNanos / 1_000_000_000.0;

            String labelKey = String.format("result=\"%s\",cache_status=\"%s\"", result, cacheStatus);
            requestCounters.computeIfAbsent(labelKey, k -> new AtomicLong(0)).incrementAndGet();

            latencySumMap.computeIfAbsent(labelKey, k -> new DoubleAdder()).add(durationSeconds);
            latencyCountMap.computeIfAbsent(labelKey, k -> new AtomicLong(0)).incrementAndGet();

            ConcurrentHashMap<Double, AtomicLong> buckets = latencyBucketCounts.computeIfAbsent(labelKey, k -> new ConcurrentHashMap<>());
            for (double bucket : latencyBuckets) {
                if (durationSeconds <= bucket) {
                    buckets.computeIfAbsent(bucket, k -> new AtomicLong(0)).incrementAndGet();
                    break;
                }
            }

            int length = (sms.sms != null) ? sms.sms.length() : 0;
            msgLengthSum.add(length);
            msgLengthCount.incrementAndGet();
        }
    }

    private String getPrediction(Sms sms) {
        try {
            var url = new URI(modelHost + "/predict");
            var c = rest.build().postForEntity(url, sms, Sms.class);
            return c.getBody().result.trim();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}