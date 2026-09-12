package com.claw.server.domain.advertising;

/**
 * 匹配上下文（A4）：一次屏匹配所需的屏与场景信息。
 *
 * <p>{@code pinned} 为是否强制置顶的上下文开关（默认 false）；当为 true 时，
 * 本次匹配产出的所有 {@link AdMatchQueue} 行均标记为置顶。置顶的另一来源是
 * 屏上已有的手动置顶队列行（{@link AdMatchQueue#getPinned()}）。
 *
 * @param screenId   目标屏 id
 * @param scenario   场景标识
 * @param routeTags  路由标签
 * @param audience   人群标签
 * @param timeSlot   时段（与 {@link AdCampaign#getTimeSlots()} 匹配用）
 * @param pinned     是否强制置顶（默认 false）
 */
public record AdContext(Long screenId, String scenario, String routeTags,
                        String audience, String timeSlot, boolean pinned) {

    /** 便捷构造：pinned 默认 false。 */
    public AdContext(Long screenId, String scenario, String routeTags,
                     String audience, String timeSlot) {
        this(screenId, scenario, routeTags, audience, timeSlot, false);
    }
}
