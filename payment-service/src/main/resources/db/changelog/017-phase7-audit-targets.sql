--liquibase formatted sql

--changeset coryd:152-payment-payout-account-audit-target-is-the-driver
-- PAYOUT_ACCOUNT_REVEALED used to name the bank account (payout_recipients.id) as its target, so the reveal didn't show
-- under the driver in the audit log. The target is now the driver (as written since phase 7); the account moves into
-- after_state as recipientId. Rows whose after_state has no driverId are left as they were.
UPDATE payment.ops_money_audit
   SET after_state = (after_state::jsonb || jsonb_build_object('recipientId', target_id, 'field', 'BANK_ACCOUNT_NUMBER'))::text,
       target_id   = after_state::jsonb ->> 'driverId'
 WHERE target_type = 'PAYOUT_ACCOUNT'
   AND after_state IS NOT NULL
   AND (after_state::jsonb ->> 'driverId') IS NOT NULL
   AND target_id <> after_state::jsonb ->> 'driverId';
