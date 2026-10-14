package com.team.dating_backend.profile.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum JobCategory {
    BUSINESS_OFFICE,
    IT_DATA,
    FINANCE_ACCOUNTING,
    SALES_MARKETING,
    RESEARCH_ENGINEERING,
    HEALTHCARE,
    EDUCATION,
    LEGAL,
    PUBLIC_ADMIN_DEFENSE,
    CULTURE_DESIGN_MEDIA,
    PERSONAL_HOSPITALITY_SERVICE,
    MANUFACTURING_CONSTRUCTION,
    TRANSPORT_LOGISTICS,
    AGRICULTURE_FISHERIES,
    WELFARE_CARE_RELIGION,
    STUDENT,
    JOB_SEEKING,
    NOT_EMPLOYED,
    OTHER;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static JobCategory fromCode(String code) {
        return JobCategory.valueOf(code);
    }
}
