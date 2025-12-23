# SMS Checker / app-frontend

## Supported arguments

- **PORT** - RUNTIME - Set the port used by nginx (default is `8080`)
- **MODEL_HOST** - RUNTIME - Set the model host (e.g. `http://localhost:8081`)
- **ENABLE_CACHE** - RUNTIME - Enable caching of model requests (`True` or `False`)
- **GITHUB_ACTOR** - BUILD-TIME - The Github actor
- **GITHUB_TOKEN** - BUILD-TIME - A github token required for maven authentication.

## Building the container

```bash
docker build \
  -t app-service \
  --build-arg GITHUB_ACTOR=your-github-username \
  --build-arg GITHUB_TOKEN=your-github-token \
  .
```

## Running the container

```bash
docker run -p 8080:8080 -e MODEL_HOST="http://localhost:8081" app-service
```
