# notification-service

Push (FCM), SMS (Termii), WhatsApp (Termii templates, SMS fallback) and email delivery, the in-app notification feed,
notification preferences, SOS and safety alerts to contacts and ops, emailed reports, and ops broadcasts.

- Port `9105`, context path `/notification`. Health: `GET /notification/actuator/health`.
- Schema `notification` (Liquibase changelogs in `src/main/resources/db/changelog`, changeset ids numbered across all services).
- Channels and fallbacks: `docs/whatsapp-notifications.md`. Broadcasts: `docs/ops-console/broadcasts.md`.
- Most sends are driven by Kafka events from the other services; every attempt is recorded in `notification_log`.

## Deploying

`develop` deploys to dev through `.github/workflows/deploy-dev.yml` when files under `notification-service/` (or the
shared parts of `common/`) change. The deploy restarts the service and waits up to 10 minutes for the health check
before rolling back to the previous jar.
