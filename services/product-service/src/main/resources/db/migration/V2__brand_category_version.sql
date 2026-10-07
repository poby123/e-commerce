-- Step 6: facets for search (brand, category) and a version for the search index.
-- version is JPA's optimistic-lock version; search-service uses it as Elasticsearch's external version, so an
-- older or repeated ProductChanged can never overwrite a newer document.
ALTER TABLE products
    ADD COLUMN brand    VARCHAR(50) NOT NULL DEFAULT '' AFTER description,
    ADD COLUMN category VARCHAR(20) NOT NULL DEFAULT 'ETC' AFTER brand,
    ADD COLUMN version  BIGINT      NOT NULL DEFAULT 0;
