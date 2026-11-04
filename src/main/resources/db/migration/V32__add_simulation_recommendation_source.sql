ALTER TABLE recommendation_items
    ADD COLUMN simulation_requested BOOLEAN NOT NULL DEFAULT FALSE;
