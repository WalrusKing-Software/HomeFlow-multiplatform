# Convenience wrappers around Docker Compose + Gradle. See __docs/DOCKER.md and
# __docs/DEPLOYMENT.md. The dev overlay is applied ONLY by `make dev` — it is
# never auto-merged, so `make prod` runs the hardened base stack alone.

.PHONY: dev prod build down logs migrate

dev:   ## Start the stack with the dev overlay (debug ports, bind mounts)
	docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d

prod:  ## Start the production stack (base file only)
	docker compose up -d

build: ## Build all images
	docker compose build

down:  ## Stop the stack (preserves volumes)
	docker compose down

logs:  ## Follow backend logs
	docker compose logs -f backend

migrate: ## Run Flyway migrations (DB must be reachable; see DEPLOYMENT.md for prod)
	./gradlew :server:flywayMigrate
