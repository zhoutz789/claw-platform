package com.claw.server.domain.advertising;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 素材上传服务（A2）：商家/用户上传广告素材，落地为 {@link AdCreative}（source=UPLOAD）。
 */
@Service
@RequiredArgsConstructor
public class AdCreativeService {

    private final AdCreativeRepository adCreativeRepository;
    private final AdAccountRepository adAccountRepository;

    /**
     * 上传素材。账户不存在时抛 {@code IllegalArgumentException("ad.account.not.found:...")}。
     *
     * @return 持久化后的 {@link AdCreative}
     */
    @Transactional
    public AdCreative uploadCreative(Long accountId, String type, String fileUrl, String mime,
                                     Integer durationSec, Boolean whiteBg, String editableJson) {
        if (!adAccountRepository.existsById(accountId)) {
            throw new IllegalArgumentException("ad.account.not.found:" + accountId);
        }
        AdCreative creative = AdCreative.builder()
                .accountId(accountId)
                .type(type)
                .fileUrl(fileUrl)
                .mime(mime)
                .durationSec(durationSec)
                .whiteBg(whiteBg == null ? false : whiteBg)
                .source("UPLOAD")
                .editableJson(editableJson)
                .build();
        return adCreativeRepository.save(creative);
    }

    /**
     * 按 id 获取素材。
     *
     * @throws IllegalArgumentException 素材不存在
     */
    public AdCreative get(Long id) {
        return adCreativeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("ad.creative.not.found:" + id));
    }
}
