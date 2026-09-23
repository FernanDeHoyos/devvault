-- V8__create_plugin_descriptors.sql

CREATE TABLE plugin_descriptors (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                  VARCHAR(80) UNIQUE NOT NULL,
    target_marker_files   VARCHAR(255) NOT NULL,
    version               VARCHAR(20) NOT NULL,
    enabled               BOOLEAN NOT NULL DEFAULT true
);