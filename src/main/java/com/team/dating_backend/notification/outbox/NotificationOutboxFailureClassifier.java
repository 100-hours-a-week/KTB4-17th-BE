package com.team.dating_backend.notification.outbox;

import com.team.dating_backend.notification.enums.NotificationFailureCode;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.stereotype.Component;

@Component
public class NotificationOutboxFailureClassifier {

    public NotificationFailureCode classify(Exception failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean timeout = false;
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            if (current instanceof AuthenticationException || current instanceof AuthorizationException
                || current instanceof ConfigException || current instanceof SerializationException
                || current instanceof IllegalArgumentException) {
                return NotificationFailureCode.OUTBOX_PUBLISH_REQUIRES_ATTENTION;
            }
            timeout |= current instanceof TimeoutException
                || current instanceof org.apache.kafka.common.errors.TimeoutException;
            current = current.getCause();
        }
        if (timeout) {
            return NotificationFailureCode.PUBLISH_RESULT_TIMEOUT;
        }
        return failure instanceof ExecutionException
            ? NotificationFailureCode.PUBLISH_RESULT_FAILED
            : NotificationFailureCode.PUBLISH_CALL_FAILED;
    }
}
