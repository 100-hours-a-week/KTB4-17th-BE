package com.team.dating_backend.recommendation.repository;

final class RecommendationEligibilityQuery {

    static final String ELIGIBLE_CANDIDATE_PREDICATE = """
        candidate.status = com.team.dating_backend.user.enums.UserStatus.ACTIVE
          AND candidate.id <> :requesterUserId
          AND NOT EXISTS (
              SELECT userBlock.id FROM UserBlock userBlock
              WHERE (userBlock.blockerUserId = :requesterUserId AND userBlock.blockedUserId = candidate.id)
                 OR (userBlock.blockerUserId = candidate.id AND userBlock.blockedUserId = :requesterUserId)
          )
          AND NOT EXISTS (
              SELECT memberLike.id FROM MemberLike memberLike
              WHERE memberLike.status = com.team.dating_backend.matching.enums.LikeStatus.REJECTED
                AND ((memberLike.senderId = :requesterUserId AND memberLike.receiverId = candidate.id)
                  OR (memberLike.senderId = candidate.id AND memberLike.receiverId = :requesterUserId))
          )
          AND NOT EXISTS (
              SELECT pendingLike.id FROM MemberLike pendingLike
              WHERE pendingLike.status = com.team.dating_backend.matching.enums.LikeStatus.PENDING
                AND pendingLike.senderId = :requesterUserId
                AND pendingLike.receiverId = candidate.id
          )
          AND NOT EXISTS (
              SELECT memberMatch.id FROM Match memberMatch
              WHERE (memberMatch.senderId = :requesterUserId AND memberMatch.receiverId = candidate.id)
                 OR (memberMatch.senderId = candidate.id AND memberMatch.receiverId = :requesterUserId)
          )
          AND NOT EXISTS (
              SELECT recommendationPass.id FROM RecommendationPass recommendationPass
              WHERE recommendationPass.passerUserId = :requesterUserId
                AND recommendationPass.passedUserId = candidate.id
          )
        """;

    private RecommendationEligibilityQuery() {}
}
