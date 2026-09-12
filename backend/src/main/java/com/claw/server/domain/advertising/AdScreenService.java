package com.claw.server.domain.advertising;

import com.claw.server.common.enums.ScreenTerminalType;
import com.claw.server.common.enums.ScreenType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 屏注册服务（A3）：资产 AD_SCREEN 设备与外部手机 app 同为一条 {@link AdScreen} 记录。
 */
@Service
@RequiredArgsConstructor
public class AdScreenService {

    private final AdScreenRepository adScreenRepository;

    /**
     * 注册一个广告屏，状态默认 ONLINE。
     * 当 {@code terminalType==MOBILE} 时允许 {@code deviceId}/{@code assetId} 为 null（外部手机 app）。
     */
    @Transactional
    public AdScreen registerScreen(ScreenTerminalType terminalType, Long deviceId, Long assetId,
                                   ScreenType screenType, String geofence, String capabilities,
                                   Long ownerId) {
        AdScreen screen = AdScreen.builder()
                .terminalType(terminalType)
                .deviceId(deviceId)
                .assetId(assetId)
                .screenType(screenType)
                .geofence(geofence)
                .capabilities(capabilities)
                .ownerId(ownerId)
                .status("ONLINE")
                .build();
        return adScreenRepository.save(screen);
    }

    /**
     * 列出全部已注册广告屏。
     */
    public List<AdScreen> listScreens() {
        return adScreenRepository.findAll();
    }
}
