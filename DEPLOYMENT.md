# Marquez Deployment Guide

This guide provides comprehensive instructions for deploying Marquez in various environments.

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Quick Deployment](#quick-deployment)
3. [Production Deployment](#production-deployment)
4. [Validation](#validation)
5. [Troubleshooting](#troubleshooting)

## Prerequisites

Before deploying Marquez, ensure you have the following installed:

- **Docker**: Version 20.10 or higher
- **Docker Compose**: Version 2.0 or higher
- **Java**: Version 17 (for building from source)
- **PostgreSQL**: Version 14 (if deploying without Docker)

## Quick Deployment

### Using Docker Compose (Recommended)

1. **Clone the repository**:
   ```bash
   git clone https://github.com/swar00pduthks/marquez.git
   cd marquez
   ```

2. **Start all services**:
   ```bash
   ./docker/up.sh
   ```

   This will start:
   - Marquez API on port 5000
   - Marquez Admin API on port 5001
   - Web UI on port 3000
   - PostgreSQL database on port 5432

3. **Verify deployment**:
   - API: http://localhost:5000
   - Admin API: http://localhost:5001/healthcheck
   - Web UI: http://localhost:3000

### With Sample Data

To deploy with seed data for testing:

```bash
./docker/up.sh --seed
```

### Building from Source

To build and deploy from source:

```bash
./docker/up.sh --build
```

## Production Deployment

### Environment Configuration

1. **Create environment file**:
   ```bash
   cp .env.example .env
   ```

2. **Configure environment variables**:
   ```bash
   # API Configuration
   API_PORT=5000
   API_ADMIN_PORT=5001
   
   # Web UI Configuration
   WEB_PORT=3000
   
   # Database Configuration
   POSTGRES_PORT=5432
   POSTGRES_DB=marquez
   POSTGRES_USER=marquez
   POSTGRES_PASSWORD=<strong-password>
   
   # Search Configuration (optional)
   SEARCH_ENABLED=true
   SEARCH_PORT=9200
   
   # Image Tag
   TAG=0.51.1
   ```

### Deployment Options

#### Option 1: Docker Compose with Custom Configuration

1. **Modify docker-compose.yml** for production settings
2. **Configure volumes** for persistent data
3. **Set up networking** for external access
4. **Deploy**:
   ```bash
   docker compose up -d
   ```

#### Option 2: Kubernetes with Helm

Marquez includes a Helm chart for Kubernetes deployment:

```bash
cd chart
helm install marquez ./marquez
```

For custom values:

```bash
helm install marquez ./marquez -f custom-values.yaml
```

#### Option 3: Manual Deployment

1. **Set up PostgreSQL database**:
   ```bash
   createdb marquez
   ```

2. **Configure Marquez**:
   ```bash
   cp marquez.example.yml marquez.yml
   # Edit marquez.yml with your database credentials
   ```

3. **Build the application**:
   ```bash
   ./gradlew build
   ```

4. **Run migrations**:
   ```bash
   java -jar api/build/libs/marquez-api-*.jar db migrate marquez.yml
   ```

5. **Start the API server**:
   ```bash
   ./gradlew :api:runShadow
   ```

### Security Considerations

1. **Database Security**:
   - Use strong passwords
   - Enable SSL/TLS for database connections
   - Restrict network access to database

2. **API Security**:
   - Implement authentication/authorization (not enabled by default)
   - Use HTTPS/TLS in production
   - Configure CORS appropriately

3. **Network Security**:
   - Use firewalls to restrict access
   - Deploy in private networks when possible
   - Use reverse proxy (nginx, Traefik) for external access

## Validation

### Health Checks

1. **API Health Check**:
   ```bash
   curl http://localhost:5001/healthcheck
   ```

   Expected response:
   ```json
   {
     "deadlocks": {
       "healthy": true
     },
     "postgresql": {
       "healthy": true
     }
   }
   ```

2. **Database Connection**:
   ```bash
   docker exec marquez-db psql -U marquez -c "SELECT version();"
   ```

3. **API Endpoint Test**:
   ```bash
   curl http://localhost:5000/api/v1/namespaces
   ```

### Verification Steps

Run the deployment validation script:

```bash
./deployment/validate-deployment.sh
```

This script checks:
- All services are running
- Health endpoints are responsive
- Database connectivity
- API endpoints are accessible
- Web UI is accessible

## Troubleshooting

### Common Issues

1. **Port Conflicts**:
   ```bash
   # Change ports in docker/up.sh
   ./docker/up.sh --api-port 9000 --db-port 15432
   ```

2. **Database Connection Issues**:
   - Check if PostgreSQL is running
   - Verify credentials in configuration
   - Check network connectivity

3. **Memory Issues**:
   - Increase Docker memory allocation
   - Adjust JVM heap size in marquez.yml

4. **Migration Failures**:
   - Check database logs
   - Verify database user permissions
   - Run migrations manually for debugging

### Logs

View logs for debugging:

```bash
# All services
docker compose logs -f

# Specific service
docker compose logs -f api

# Database logs
docker compose logs -f db
```

### Clean Restart

To completely reset the deployment:

```bash
./docker/down.sh
docker volume prune -f
./docker/up.sh --build
```

## Additional Resources

- [Marquez Documentation](https://marquezproject.github.io/marquez/)
- [OpenLineage Documentation](https://openlineage.io/)
- [API Documentation](https://marquezproject.github.io/marquez/openapi.html)

## Support

For issues and questions:
- GitHub Issues: https://github.com/swar00pduthks/marquez/issues
- Slack: MarquezProject Slack channel

---

**Note**: This is a custom fork of Marquez. For the official project, visit [MarquezProject/marquez](https://github.com/MarquezProject/marquez).
