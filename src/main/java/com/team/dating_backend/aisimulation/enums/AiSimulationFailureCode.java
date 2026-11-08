package com.team.dating_backend.aisimulation.enums;

public enum AiSimulationFailureCode {
    DLT_RECORDED,
    RETRY_PUBLISH_FAILED,
    DLT_PUBLISH_FAILED,
    FINAL_STATE_UPDATE_FAILED,
    RECOVERY_FAILED,
    ALERT_SUBMISSION_REJECTED,
    ALERT_WEBHOOK_FAILED,
    REPORTING_FAILED
}
