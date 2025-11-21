# SMS Checker / app-frontend

## Supported arguments

- **PORT** - Set the port used by nginx (default is `8080`)
- **MODEL_HOST** - Set the model host (e.g. `http://localhost:8081`)
- **GITHUB_ACTOR**
- **GITHUB_TOKEN**

## Building the container

```bash
docker build -t app-service .
```

## Running the container

```bash
docker run -p 8080:8080 -e MODEL_HOST="http://localhost:8081" app-service
```
