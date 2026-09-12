package com.claw.server.domain.advertising;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商家商品素材接入服务（A6）：消费 {@link ProductMediaEvent}，为商家 {@link AdAccount}
 * 生成 PRODUCT 来源 {@link AdCreative}。
 */
@Service
@RequiredArgsConstructor
public class AdProductMediaService {

    private final AdCreativeRepository adCreativeRepository;
    private final AdAccountRepository adAccountRepository;

    /**
     * 接入一条商品素材事件。
     *
     * @throws IllegalArgumentException 商家账户不存在（{@code ad.account.not.found:ownerId}）
     */
    @Transactional
    public AdCreative ingestProductMedia(ProductMediaEvent evt) {
        AdAccount account = adAccountRepository.findByOwnerId(evt.ownerId())
                .orElseThrow(() -> new IllegalArgumentException("ad.account.not.found:" + evt.ownerId()));

        String type = evt.mime() != null && evt.mime().startsWith("video") ? "VIDEO" : "IMAGE";
        AdCreative creative = AdCreative.builder()
                .accountId(account.getId())
                .type(type)
                .fileUrl(evt.mediaUrl())
                .mime(evt.mime())
                .whiteBg(evt.whiteBg() == null ? false : evt.whiteBg())
                .source("PRODUCT")
                .build();
        return adCreativeRepository.save(creative);
    }
}
