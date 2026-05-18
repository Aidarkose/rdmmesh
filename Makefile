.DEFAULT_GOAL := help

# rdmmesh — developer entrypoints. All Java work runs through bin/mvn,
# which executes Maven inside the official maven:3.9-eclipse-temurin-21
# image so JDK/Maven are not required on the host machine.

MVN          ?= ./bin/mvn
COMPOSE_FILE ?= docker/docker-compose.yml
COMPOSE      ?= docker compose -f $(COMPOSE_FILE)

.PHONY: help
help: ## Show this help
	@awk 'BEGIN {FS = ":.*?## "} /^[a-zA-Z0-9_.-]+:.*?## / {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

.PHONY: build
build: ## Compile + package every Maven module
	$(MVN) -T 1C clean package

.PHONY: compile
compile: ## Compile only (skips tests)
	$(MVN) -T 1C -DskipTests compile

.PHONY: test
test: ## Run unit tests
	$(MVN) -T 1C test

.PHONY: verify
verify: ## Full build incl. ArchUnit + integration tests
	$(MVN) -T 1C verify

.PHONY: format
format: ## Apply Spotless code formatting
	$(MVN) spotless:apply

.PHONY: format-check
format-check: ## Verify Spotless formatting
	$(MVN) spotless:check

.PHONY: codegen
codegen: ## Run JSON Schema codegen for rdmmesh-spec (Java POJO)
	$(MVN) -pl rdmmesh-spec -am generate-sources

.PHONY: codegen-ts
codegen-ts: ## Run JSON Schema codegen for TypeScript (rdmmesh-ui/src/generated/)
	cd rdmmesh-ui && npm run codegen

.PHONY: ui-install
ui-install: ## Install UI npm dependencies
	cd rdmmesh-ui && npm install

.PHONY: up
up: ## Start the dev compose stack (postgres, keycloak, rdmmesh-service)
	$(COMPOSE) up -d

.PHONY: down
down: ## Stop the dev compose stack
	$(COMPOSE) down

.PHONY: logs
logs: ## Tail compose logs
	$(COMPOSE) logs -f --tail=200

.PHONY: psql
psql: ## Open psql against the dev database (admin role)
	$(COMPOSE) exec postgres psql -U rdmmesh_admin -d rdmmesh

.PHONY: kc-token
kc-token: ## Issue a dev access token (KC_USER=dev-author KC_PASS=dev → JWT). Requires `make up` first.
	@curl -s -X POST "http://localhost:8090/realms/bank/protocol/openid-connect/token" \
	    -d "grant_type=password" \
	    -d "client_id=rdmmesh-ui" \
	    -d "username=$${KC_USER:-dev-author}" \
	    -d "password=$${KC_PASS:-dev}" \
	    -d "scope=openid" | (jq -r .access_token 2>/dev/null || cat)

.PHONY: kc-admin
kc-admin: ## Open Keycloak admin console URL (login admin/admin)
	@echo "Keycloak admin: http://localhost:8090/admin (admin/admin)"
	@echo "Realm:          http://localhost:8090/realms/bank"
	@echo "Token endpoint: http://localhost:8090/realms/bank/protocol/openid-connect/token"
	@echo "JWKS:           http://localhost:8090/realms/bank/protocol/openid-connect/certs"

.PHONY: ui
ui: ## Run the React dev server
	cd rdmmesh-ui && npm run dev

.PHONY: clean
clean: ## Remove build artifacts (Maven target/ and Vite dist/)
	$(MVN) clean
	rm -rf rdmmesh-ui/dist rdmmesh-ui/node_modules/.vite
