package com.claw.server.domain.advertising;

/**
 * TaskAd 接入输入（A7）：来自任务域的一次性广告投放请求。
 * 本服务只读该记录，不依赖任何任务域类。
 *
 * @param id                       任务广告 id
 * @param advertiserOwnerId        广告主 owner id
 * @param mediaUrl                 素材地址
 * @param mime                     媒体类型
 * @param screenType               期望屏类型（透传，本期供匹配参考）
 * @param displayDurationSeconds   展示时长（秒）
 * @param ownerId                  归属 owner id
 */
public record TaskAdInput(Long id, Long advertiserOwnerId, String mediaUrl, String mime,
                          String screenType, int displayDurationSeconds, Long ownerId) {
}
