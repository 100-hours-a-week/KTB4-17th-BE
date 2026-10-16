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
              SELECT userLike.id FROM UserLike userLike
              WHERE userLike.status = com.team.dating_backend.matching.enums.LikeStatus.REJECTED
                AND ((userLike.senderId = :requesterUserId AND userLike.receiverId = candidate.id)
                  OR (userLike.senderId = candidate.id AND userLike.receiverId = :requesterUserId))
          )
          AND NOT EXISTS (
              SELECT pendingLike.id FROM UserLike pendingLike
              WHERE pendingLike.status = com.team.dating_backend.matching.enums.LikeStatus.PENDING
                AND pendingLike.senderId = :requesterUserId
                AND pendingLike.receiverId = candidate.id
          )
          AND NOT EXISTS (
              SELECT userMatch.id FROM Match userMatch
              WHERE (userMatch.senderId = :requesterUserId AND userMatch.receiverId = candidate.id)
                 OR (userMatch.senderId = candidate.id AND userMatch.receiverId = :requesterUserId)
          )
          AND NOT EXISTS (
              SELECT recommendationPass.id FROM RecommendationPass recommendationPass
              WHERE recommendationPass.passerUserId = :requesterUserId
                AND recommendationPass.passedUserId = candidate.id
          )
        """;

    private RecommendationEligibilityQuery() {}
}
