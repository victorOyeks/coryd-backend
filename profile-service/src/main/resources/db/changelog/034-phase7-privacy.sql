--liquibase formatted sql

--changeset coryd:150-profile-pii-reveal-log
-- NDPA 2023: every deliberate reveal of a full NIN, licence number or date of birth in the ops console - who, whose, which,
-- why (the value itself is never stored). Read by the unified audit log as PII_REVEALED (category PRIVACY).
CREATE TABLE IF NOT EXISTS profile.pii_reveal_log
(
    id           VARCHAR(36) PRIMARY KEY,
    custom_id    VARCHAR(20) UNIQUE,
    user_id      VARCHAR(36)  NOT NULL,
    field        VARCHAR(30)  NOT NULL,
    actor_id     VARCHAR(36)  NOT NULL,
    actor_name   VARCHAR(120),
    reason       VARCHAR(500) NOT NULL,
    masked_value VARCHAR(40),
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_pii_reveal_log_created ON profile.pii_reveal_log (created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_pii_reveal_log_user ON profile.pii_reveal_log (user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_pii_reveal_log_actor ON profile.pii_reveal_log (actor_id, created_at);

--changeset coryd:151-profile-mask-ids-in-provider-summaries
-- Smile ID summaries written before phase 7 spelled out licence numbers ("Licence number on the card is LAG123..., driver
-- entered ...", "Genuine licence ..., number LAG123..."), and the NIN-vs-licence cross-check full dates of birth. The console
-- shows these summaries (and the decision notes that quote them), so keep only the last four characters of a number and
-- the year of a date of birth - the way every other masked identifier reads.
UPDATE profile.verification_checks
   SET provider_summary = regexp_replace(regexp_replace(regexp_replace(provider_summary,
           'Licence number on the card is [A-Za-z0-9/-]*([A-Za-z0-9]{4})(?![A-Za-z0-9]), driver entered [A-Za-z0-9/-]*([A-Za-z0-9]{4})(?![A-Za-z0-9])',
           'Licence number on the card is ' || repeat(chr(8226), 4) || '\1, driver entered ' || repeat(chr(8226), 4) || '\2', 'g'),
           '(Genuine licence in the name of [^,]*), number [A-Za-z0-9/-]*([A-Za-z0-9]{4})(?![A-Za-z0-9])', '\1, number ' || repeat(chr(8226), 4) || '\2', 'g'),
           'NIMC date of birth (\d{4})-\d{2}-\d{2}, licence (\d{4})-\d{2}-\d{2}', 'NIMC and licence dates of birth differ (born \1 / \2)', 'g')
 WHERE provider_summary LIKE '%Licence number on the card is %' OR provider_summary LIKE '%Genuine licence in the name of %'
    OR provider_summary LIKE '%NIMC date of birth %';
UPDATE profile.verification_decision_log
   SET note = regexp_replace(regexp_replace(regexp_replace(note,
           'Licence number on the card is [A-Za-z0-9/-]*([A-Za-z0-9]{4})(?![A-Za-z0-9]), driver entered [A-Za-z0-9/-]*([A-Za-z0-9]{4})(?![A-Za-z0-9])',
           'Licence number on the card is ' || repeat(chr(8226), 4) || '\1, driver entered ' || repeat(chr(8226), 4) || '\2', 'g'),
           '(Genuine licence in the name of [^,]*), number [A-Za-z0-9/-]*([A-Za-z0-9]{4})(?![A-Za-z0-9])', '\1, number ' || repeat(chr(8226), 4) || '\2', 'g'),
           'NIMC date of birth (\d{4})-\d{2}-\d{2}, licence (\d{4})-\d{2}-\d{2}', 'NIMC and licence dates of birth differ (born \1 / \2)', 'g')
 WHERE note LIKE '%Licence number on the card is %' OR note LIKE '%Genuine licence in the name of %' OR note LIKE '%NIMC date of birth %';
