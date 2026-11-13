# payment-service

Card payments (Paystack), fare holds, refunds, driver earnings and settlement, payouts, and the ops money endpoints.

- Port `9104`, context path `/payment`. Health: `GET /payment/actuator/health`.
- Schema `payment` (Liquibase changelogs in `src/main/resources/db/changelog`, changeset ids numbered across all services).
- `/internal/**` is service-to-service only and needs `X-Internal-Api-Key` (`INTERNAL_API_KEY`) on every environment.
- Ops endpoints behind `/ops/money/**` (ops-service) are documented in `docs/ops-console/money.md`.
- Platform settings it follows (commission %, money approval threshold) come from trip-service; see
  `docs/ops-console/settings.md`.

## Deploying

`develop` deploys to dev through `.github/workflows/deploy-dev.yml` when files under `payment-service/` (or the shared
parts of `common/`) change. The deploy restarts the service and waits up to 10 minutes for the health check before
rolling back to the previous jar.
