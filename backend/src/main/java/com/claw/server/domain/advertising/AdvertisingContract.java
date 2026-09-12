package com.claw.server.domain.advertising;

/**
 * 广告子系统接口契约（contract artifact，无 DB 依赖）。
 *
 * <p>约定平台（claw-server）与广告子系统之间的两类事件：
 * <ul>
 *   <li>{@link #TOPIC_PRODUCT_MEDIA_PUBLISHED}：商家发布商品素材，平台据此生成 PRODUCT 素材；</li>
 *   <li>{@link #TOPIC_AD_EXPOSURE}：广告屏上报曝光/播放，平台据此计费与结算。</li>
 * </ul>
 * 本类仅承载常量与契约记录（record），并提供 {@link #pushCreative} 的方法签名（javadoc-only），
 * 实际下发由平台推送网关实现，不在本子系统内。
 */
public final class AdvertisingContract {

    private AdvertisingContract() {
    }

    /**
     * 商家商品素材发布事件 Topic。
     * payload（JSON）：{@code {productId, ownerId, mediaUrl, mime, whiteBg}}。
     */
    public static final String TOPIC_PRODUCT_MEDIA_PUBLISHED = "claw.merchant.product.media.published";

    /**
     * 广告曝光上报事件 Topic。
     * payload（JSON）：{@link ExposureReport} 的字段集合。
     */
    public static final String TOPIC_AD_EXPOSURE = "claw.advertising.exposure.reported";

    /**
     * 屏推送目标：标识一个具体广告屏及其挂载资产。
     *
     * @param screenId      广告屏 id（{@link AdScreen#getId()}）
     * @param assetId       挂载资产 id（ASSET 终端必填）
     * @param terminalType  终端类型（"ASSET" / "MOBILE"）
     */
    public record ScreenPushTarget(Long screenId, Long assetId, String terminalType) {
    }

    /**
     * 曝光上报：广告屏一次播放/曝光的结果。
     *
     * @param campaignId  计划 id
     * @param creativeId  素材 id
     * @param screenId    屏 id
     * @param durationMs  本次播放时长（毫秒）
     * @param playCount   播放次数
     * @param clickCount  点击次数
     */
    public record ExposureReport(Long campaignId, Long creativeId, Long screenId,
                                 long durationMs, int playCount, int clickCount) {
    }

    /**
     * 平台向指定屏推送一个素材（契约方法，javadoc-only，无实现）。
     *
     * <p>实现方（平台推送网关）负责将 {@code creative} 下发到 {@code target} 屏，并保证
     * 素材格式/尺寸/时长与目标屏能力（{@link AdScreen#getCapabilities()}）兼容。广告子系统
     * 仅消费由此产生的 {@link #TOPIC_AD_EXPOSURE} 曝光上报进行计费。
     *
     * @param target   目标屏
     * @param creative 待推送素材
     */
    public void pushCreative(ScreenPushTarget target, AdCreative creative) {
        throw new UnsupportedOperationException(
                "contract-only method; implemented by the platform push gateway");
    }
}
