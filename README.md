# App Service

A Spring Boot application that provides SMS spam detection by forwarding requests to a model service.

## Supported Arguments

| Argument | Type | Description |
|----------|------|-------------|
| `PORT` | Runtime | Set the port used (default is `8080`) |
| `MODEL_HOST` | Runtime | Set the model host (e.g. `http://localhost:8081`) |
| `APP_VERSION` | Runtime | Set the app version label for metrics (default is `stable`) |
| `GITHUB_ACTOR` | Build-time | The Github actor |
| `GITHUB_TOKEN` | Build-time | A github token required for maven authentication |

## Building the Container

```bash
docker build \
  -t app-service \
  --build-arg GITHUB_ACTOR=your-github-username \
  --build-arg GITHUB_TOKEN=your-github-token \
  .
```

## Running the Container

```bash
docker run -p 8080:8080 -e MODEL_HOST="http://localhost:8081" app-service
```

## Metric Endpoints

### SMS Prediction

**POST** `/sms`

Submit an SMS message for spam prediction.

Request body:
```json
{
  "sms": "Your message text here"
}
```

Response:
```json
{
  "sms": "Your message text here",
  "result": "spam" // or "ham"
}
```

### Page View Tracking

**POST** `/sms/pageview`

Track a UI page view. Returns `ok` on success.

### Metrics Endpoint

**GET** `/sms/metrics`

Returns Prometheus-formatted metrics for monitoring. Available metrics:

| Metric | Type | Description |
|--------|------|-------------|
| `app_sms_active_requests` | Gauge | Number of requests currently being processed |
| `app_sms_requests_total` | Counter | Total number of SMS requests (labeled by result: spam/ham) |
| `app_sms_latency_seconds` | Histogram | Time taken to process SMS predictions |
| `app_sms_message_length` | Summary | Distribution of SMS message lengths |
| `app_ui_page_views_total` | Counter | Total number of UI page views |

All metrics include a `version` label indicating the app version.
