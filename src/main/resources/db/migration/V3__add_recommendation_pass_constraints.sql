ALTER TABLE recommendation_passes
    ADD CONSTRAINT uk_recommendation_pass_direction UNIQUE (passer_user_id, passed_user_id),
    ADD CONSTRAINT chk_recommendation_pass_not_self CHECK (passer_user_id <> passed_user_id),
    ADD CONSTRAINT fk_recommendation_pass_passer FOREIGN KEY (passer_user_id) REFERENCES users (id),
    ADD CONSTRAINT fk_recommendation_pass_passed FOREIGN KEY (passed_user_id) REFERENCES users (id);
