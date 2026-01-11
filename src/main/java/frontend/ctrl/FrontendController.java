package frontend.ctrl;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import frontend.data.Sms;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Controller
@RequestMapping(path = "/sms")
public class FrontendController {

    private String modelHost;
    private RestTemplateBuilder rest;
    private boolean cacheEnabled;
    private ConcurrentHashMap<String, String> predictionCache;

    // Monitor Objects
    private final MeterRegistry meterRegistry;
    private final AtomicInteger activeRequests;
    private final String appVersion;

    public FrontendController(RestTemplateBuilder rest, Environment env, MeterRegistry meterRegistry) {
        this.rest = rest;
        this.modelHost = env.getProperty("MODEL_HOST");
        this.cacheEnabled = Boolean.parseBoolean(env.getProperty("ENABLE_CACHE", "false"));
        this.predictionCache = new ConcurrentHashMap<>();
        this.meterRegistry = meterRegistry;
        this.appVersion = env.getProperty("APP_VERSION", "stable");

        // Initialize Gauge - active requests
        this.activeRequests = new AtomicInteger(0);
        Gauge.builder("app_sms_active_requests", activeRequests, AtomicInteger::get)
                .description("Number of requests currently being processed")
                .tag("version", appVersion)
                .register(meterRegistry);

        // Initialize Gauge - cache size
        Gauge.builder("app_cache_size", predictionCache, ConcurrentHashMap::size)
                .description("Current number of entries in the cache")
                .tag("version", appVersion)
                .register(meterRegistry);

        assertModelHost();
        System.out.printf("Cache enabled: %s\n", cacheEnabled);
        System.out.printf("App version: %s\n", appVersion);
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

    @PostMapping({ "", "/" })
    @ResponseBody
    public Sms predict(@RequestBody Sms sms) {
        activeRequests.incrementAndGet();
        Timer.Sample sample = Timer.start(meterRegistry);

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

            // Counter with labels: version, result (spam/ham), cache_status
            Counter.builder("app_sms_requests_total")
                    .description("Total number of SMS requests")
                    .tag("version", appVersion)
                    .tag("result", result)
                    .tag("cache_status", cacheStatus)
                    .register(meterRegistry)
                    .increment();

            // Timer/Histogram with labels and percentile histograms enabled
            sample.stop(Timer.builder("app_sms_latency_seconds")
                    .description("Time taken to process SMS prediction")
                    .tag("version", appVersion)
                    .tag("result", result)
                    .tag("cache_status", cacheStatus)
                    .publishPercentileHistogram(true)  // THIS ENABLES HISTOGRAM BUCKETS
                    .publishPercentiles(0.5, 0.95, 0.99)  // Also publish specific percentiles
                    .register(meterRegistry));

            // Explicit Histogram for SMS message length distribution
            DistributionSummary.builder("app_sms_message_length")
                    .description("Distribution of SMS message lengths")
                    .tag("version", appVersion)
                    .tag("result", result)
                    .publishPercentileHistogram(true)
                    .register(meterRegistry)
                    .record(sms.sms != null ? sms.sms.length() : 0);
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