CREATE INDEX idx_likes_sender_status_receiver
    ON likes (sender_id, status, receiver_id);

CREATE INDEX idx_likes_receiver_status_sender
    ON likes (receiver_id, status, sender_id);
