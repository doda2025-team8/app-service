# SMS Checker / app-frontend

## Supported arguments

- **PORT** - Set the port used by nginx (default is `8081`)
- **MODEL_HOST** - Set the model host (e.g. `http://localhost:8081`)

## Building the container

```bash
docker build -t app-service .
```

## Running the container

```bash
docker run -p 8081:8081 -e MODEL_HOST="http://localhost:8081" app-service
```
