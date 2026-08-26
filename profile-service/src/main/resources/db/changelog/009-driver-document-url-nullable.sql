--liquibase formatted sql

--changeset coryd:009-driver-document-url-nullable
-- Approved documents get their URL nulled out and the asset deleted from
-- Cloudinary to reclaim storage (see VerificationAggregationService.applyDecision) -
-- document_url can no longer be NOT NULL once that happens.
ALTER TABLE profile.driver_documents
    ALTER COLUMN document_url DROP NOT NULL;
