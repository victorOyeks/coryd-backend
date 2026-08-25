--liquibase formatted sql

--changeset coryd:010-payout-recipient-bank-code
ALTER TABLE payment.payout_recipients ADD COLUMN IF NOT EXISTS bank_code VARCHAR(10) NOT NULL DEFAULT '';

--changeset coryd:011-payout-gateway-reference
ALTER TABLE payment.payouts ADD COLUMN IF NOT EXISTS gateway_reference VARCHAR(100) UNIQUE;

--changeset coryd:012-earnings-transaction-route-post-id
ALTER TABLE payment.earnings_transactions ADD COLUMN IF NOT EXISTS route_post_id VARCHAR(36);
