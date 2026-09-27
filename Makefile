.PHONY: help up down restart logs backend-build backend-test ai-test frontend-build test

help:
	@echo "RepoPilot — Available Commands:"
	@echo "  make up             - Build and start all services via docker-compose"
	@echo "  make down           - Stop all docker services"
	@echo "  make restart        - Restart docker services"
	@echo "  make logs           - View logs from all docker containers"
	@echo "  make backend-build  - Build Java Spring Boot backend"
	@echo "  make backend-test   - Run Spring Boot tests"
	@echo "  make ai-test        - Run Python AI Service tests"
	@echo "  make frontend-build - Build Next.js frontend"
	@echo "  make test           - Run unit/integration tests for all components"

up:
	docker-compose up --build -d

down:
	docker-compose down

restart:
	docker-compose restart

logs:
	docker-compose logs -f

backend-build:
	cd backend && ./mvnw clean package -DskipTests || mvn clean package -DskipTests

backend-test:
	cd backend && ./mvnw test || mvn test

ai-test:
	cd ai-service && pytest

frontend-build:
	cd frontend && npm run build

test: backend-test ai-test
