# Convenience wrappers around Docker Compose + Gradle. See __docs/DOCKER.md and
# __docs/DEPLOYMENT.md. The dev overlay is applied ONLY by the dev-* targets — it
# is never auto-merged, so the prod-* targets run the hardened base stack alone.

DEV_COMPOSE  := docker compose -f docker-compose.yml -f docker-compose.dev.yml
PROD_COMPOSE := docker compose

.PHONY: dev-start dev-build dev-rebuild dev-reset dev-stop dev-logs \
        prod-start prod-build prod-rebuild prod-reset prod-stop prod-logs \
        dev prod build down logs \
        migrate seed-dev smoke-phase4 smoke-phase6

# --- Dev stack (docker-compose.yml + docker-compose.dev.yml: debug ports, bind mounts) ---

dev-start:   ## Start the dev stack (no build; uses whatever images already exist)
	$(DEV_COMPOSE) up -d

dev-build:   ## Build the dev stack's images without starting anything
	$(DEV_COMPOSE) build

dev-rebuild: ## Rebuild images for any changed code and recreate dev containers (keeps volumes/data)
	$(DEV_COMPOSE) up -d --build --force-recreate

dev-reset:   ## Nuke and rebuild the dev stack from scratch: stops containers, deletes dev volumes (DB/Keycloak data), rebuilds images with no cache, starts fresh
	$(DEV_COMPOSE) down -v
	$(DEV_COMPOSE) build --no-cache
	$(DEV_COMPOSE) up -d

dev-stop:    ## Stop the dev stack (preserves volumes)
	$(DEV_COMPOSE) down

dev-logs:    ## Follow dev backend logs
	$(DEV_COMPOSE) logs -f backend

# --- Prod stack (docker-compose.yml alone: the hardened base, no debug ports) ---

prod-start:   ## Start the production stack (no build; uses whatever images already exist)
	$(PROD_COMPOSE) up -d

prod-build:   ## Build the production stack's images without starting anything
	$(PROD_COMPOSE) build

prod-rebuild: ## Rebuild images for any changed code and recreate prod containers (keeps volumes/data)
	$(PROD_COMPOSE) up -d --build --force-recreate

prod-reset:   ## DESTROYS prod data (Postgres, Keycloak realm state, Caddy certs) and rebuilds fresh. Requires CONFIRM=yes.
ifneq ($(CONFIRM),yes)
	@echo "Refusing: this permanently deletes the production database, Keycloak state, and Caddy certs."
	@echo "Re-run as: make prod-reset CONFIRM=yes"
	@exit 1
endif
	$(PROD_COMPOSE) down -v
	$(PROD_COMPOSE) build --no-cache
	$(PROD_COMPOSE) up -d

prod-stop:    ## Stop the production stack (preserves volumes)
	$(PROD_COMPOSE) down

prod-logs:    ## Follow prod backend logs
	$(PROD_COMPOSE) logs -f backend

# --- Legacy aliases (kept for existing docs/scripts referencing these names) ---

dev:   dev-start    ## Alias for dev-start
prod:  prod-start    ## Alias for prod-start
build: dev-build     ## Alias for dev-build (the only buildable image is shared between dev/prod)
down:  dev-stop      ## Alias for dev-stop
logs:  dev-logs      ## Alias for dev-logs

# --- Misc ---

migrate: ## Run Flyway migrations (DB must be reachable; see DEPLOYMENT.md for prod)
	./gradlew :server:flywayMigrate

seed-dev: ## Seed the dev stack with repeatable demo data for the read MVP (creates the login user; see script header)
	bash scripts/seed-dev.sh

smoke-phase4: ## Smoke-test Phase 4 against the running dev stack (needs SMOKE_PASSWORD; see script header)
	bash scripts/smoke-phase4.sh

smoke-phase6: ## Smoke-test the full API (phases 4–6) against the dev stack (needs SMOKE_PASSWORD; see script header)
	bash scripts/smoke-phase6.sh
