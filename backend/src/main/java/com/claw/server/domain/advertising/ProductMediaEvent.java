package com.claw.server.domain.advertising;

/**
 * 商家商品素材事件（A6）：由 merchant 商品发布产生，对应
 * {@link AdvertisingContract#TOPIC_PRODUCT_MEDIA_PUBLISHED} 的 payload。
 *
 * @param productId 商品 id
 * @param ownerId   商家 owner id（用于解析 {@link AdAccount}）
 * @param mediaUrl  素材地址
 * @param mime      媒体类型（video/* → VIDEO，其余 → IMAGE）
 * @param whiteBg  是否白底
 */
public record ProductMediaEvent(Long productId, Long ownerId, String mediaUrl, String mime, Boolean whiteBg) {
}
