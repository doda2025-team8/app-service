package frontend.ctrl;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.actuate.autoconfigure.metrics.export.otlp.OtlpMetricsProperties.Meter;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import frontend.data.Sms;
import jakarta.servlet.http.HttpServletRequest;
import io.micrometer.core.instrument.Counter;
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
    private final Counter requestCounter;
    private final Counter cacheHitsCounter;
    private final Counter cacheMissesCounter;
    private final AtomicInteger activeRequests;
    private final Timer processingTimer;

    public FrontendController(RestTemplateBuilder rest, Environment env, MeterRegistry meterRegistry) {
        this.rest = rest;
        this.modelHost = env.getProperty("MODEL_HOST");
        this.cacheEnabled = Boolean.parseBoolean(env.getProperty("ENABLE_CACHE", "false"));
        this.predictionCache = new ConcurrentHashMap<>();
        this.meterRegistry = meterRegistry;

        // Get version from environment variable
        String version = env.getProperty("APP_VERSION", "stable");

        // Initialize Counters with version tag
        this.requestCounter = Counter.builder("app_sms_requests_total")
                .description("Total number of SMS requests")
                .tag("version", version)
                .register(meterRegistry);

        this.cacheHitsCounter = Counter.builder("app_cache_hits_total")
                .description("Total number of cache hits")
                .tag("version", version)
                .register(meterRegistry);

        this.cacheMissesCounter = Counter.builder("app_cache_misses_total")
                .description("Total number of cache misses")
                .tag("version", version)
                .register(meterRegistry);

        // Initialize Gauge with version tag
        this.activeRequests = new AtomicInteger(0);
        Gauge.builder("app_sms_active_requests", activeRequests, AtomicInteger::get)
                .description("Number of requests currently being processed")
                .tag("version", version)
                .register(meterRegistry);

        Gauge.builder("app_cache_size", predictionCache, ConcurrentHashMap::size)
                .description("Current number of entries in the cache")
                .tag("version", version)
                .register(meterRegistry);

        // Initialize Timer with version tag
        this.processingTimer = Timer.builder("app_sms_latency_seconds")
                .description("Time taken to predict SMS")
                .tag("version", version)
                .register(meterRegistry);

        assertModelHost();
        System.out.printf("Cache enabled: %s\n", cacheEnabled);
        System.out.printf("App version: %s\n", version);
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
        // Gauge +1 -> request started
        activeRequests.incrementAndGet();
        // Start timer
        Timer.Sample sample = Timer.start(meterRegistry);

        System.out.printf("Requesting prediction for \"%s\" ...\n", sms.sms);

        try{
            // Check cache first if enabled
            if (cacheEnabled && predictionCache.containsKey(sms.sms)) {
                sms.result = predictionCache.get(sms.sms);
                cacheHitsCounter.increment();
                System.out.printf("Cache HIT: %s\n", sms.result);
            } else {
                // Cache miss or cache disabled
                if (cacheEnabled) {
                    cacheMissesCounter.increment();
                    System.out.println("Cache MISS - calling model service");
                }
                sms.result = getPrediction(sms);
                
                // Store in cache if enabled
                if (cacheEnabled) {
                    predictionCache.put(sms.sms, sms.result);
                }
            }
            
            // Request counter +1
            requestCounter.increment();
            System.out.printf("Prediction: %s\n", sms.result);
            return sms;
        } finally {
            // Gauge -1 -> request ended
            activeRequests.decrementAndGet();
            // Stop timer
            sample.stop(processingTimer);
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