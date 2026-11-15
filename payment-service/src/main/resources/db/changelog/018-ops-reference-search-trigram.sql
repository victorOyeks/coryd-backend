--liquibase formatted sql

--changeset coryd:181-ops-money-reference-search-trigram runAlways:true splitStatements:false
-- Money -> Transactions' reference search matches anywhere in the gateway reference (lower(reference) LIKE '%term%'):
-- payments and refunds on the payment's gateway_reference, payouts on gateway_settlement_id. Trigram indexes on both let
-- it read only the matching rows instead of every payment and payout; refunds reach theirs through the payment
-- (idx_refunds_payment_transaction).
--
-- pg_trgm is used from whichever schema holds it (profile on every CoRyd database) and installed only where it's missing -
-- into profile when that schema exists, so profile-service's own indexes still find it. Never fails a deploy: a problem
-- is a WARNING and the search stays a scan. runAlways so a skipped index is built on a later start; once all exist it's a
-- catalog check. Same approach as trip-service's coryd:180.
DO $$
DECLARE
    trgm text;
BEGIN
    IF to_regclass('payment.idx_payment_transactions_reference_trgm') IS NOT NULL
            AND to_regclass('payment.idx_driver_settlements_reference_trgm') IS NOT NULL
            AND to_regclass('payment.idx_refunds_payment_transaction') IS NOT NULL THEN
        RETURN;
    END IF;

    BEGIN
        CREATE INDEX IF NOT EXISTS idx_refunds_payment_transaction ON payment.refunds (payment_transaction_id);
    EXCEPTION WHEN OTHERS THEN
        RAISE WARNING 'Refund payment index not built (%)', SQLERRM;
    END;

    SELECT n.nspname INTO trgm FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace WHERE e.extname = 'pg_trgm';
    IF trgm IS NULL THEN
        BEGIN
            IF to_regnamespace('profile') IS NOT NULL THEN
                CREATE EXTENSION IF NOT EXISTS pg_trgm SCHEMA profile;
            ELSE
                CREATE EXTENSION IF NOT EXISTS pg_trgm;
            END IF;
        EXCEPTION WHEN OTHERS THEN
            RAISE WARNING 'pg_trgm could not be installed (%) - Money reference search stays unindexed', SQLERRM;
            RETURN;
        END;
        SELECT n.nspname INTO trgm FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace WHERE e.extname = 'pg_trgm';
    END IF;

    BEGIN
        EXECUTE format('CREATE INDEX IF NOT EXISTS idx_payment_transactions_reference_trgm ON payment.payment_transactions USING gin (lower(gateway_reference) %I.gin_trgm_ops)', trgm);
        EXECUTE format('CREATE INDEX IF NOT EXISTS idx_driver_settlements_reference_trgm ON payment.driver_settlements USING gin (lower(gateway_settlement_id) %I.gin_trgm_ops)', trgm);
    EXCEPTION WHEN OTHERS THEN
        RAISE WARNING 'Money reference search trigram indexes not built (%) - the search stays a scan until a later start builds them', SQLERRM;
    END;
END $$;
