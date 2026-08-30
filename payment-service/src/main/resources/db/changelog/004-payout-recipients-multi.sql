--liquibase formatted sql

--changeset coryd:013-payout-recipients-multi
-- Was one bank account per driver (driver_id UNIQUE) - the Payout Setup screen needs several
-- saved accounts with one marked default/preferred for actual withdrawals.
ALTER TABLE payment.payout_recipients DROP CONSTRAINT IF EXISTS payout_recipients_driver_id_key;
ALTER TABLE payment.payout_recipients ADD COLUMN IF NOT EXISTS is_default BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS idx_payout_recipients_driver_id ON payment.payout_recipients (driver_id);

-- Every existing row (one per driver today, before this migration) becomes that driver's
-- default, so withdrawals and receipts keep working exactly as before for anyone who already
-- had a bank account saved.
UPDATE payment.payout_recipients SET is_default = TRUE WHERE is_default = FALSE;
