package com.claw.server.domain.advertising;

import com.claw.server.common.enums.AdCampaignStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 匹配引擎（A4）：为某屏选出 eligible 广告计划并写入 {@link AdMatchQueue}，返回有序计划 id 列表。
 *
 * <p>eligibility：{@code status==ACTIVE && spent < budget}。
 * ranking：先按置顶（pinned）降序，再按计划内素材最高权重（weight）降序。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdMatchService {

    private final AdCampaignRepository adCampaignRepository;
    private final AdCampaignCreativeRepository adCampaignCreativeRepository;
    private final AdMatchQueueRepository adMatchQueueRepository;

    @Transactional
    public List<Long> match(Long screenId, AdContext context) {
        List<AdCampaign> eligible = adCampaignRepository.findAll().stream()
                .filter(c -> AdCampaignStatus.ACTIVE.equals(c.getStatus()))
                .filter(c -> c.getSpent() != null
                        && c.getBudget() != null
                        && c.getSpent().compareTo(c.getBudget()) < 0)
                .toList();

        // 计算每个 eligible 计划的 pinned 与最高素材权重
        Map<Long, Boolean> pinnedMap = new LinkedHashMap<>();
        Map<Long, Integer> weightMap = new LinkedHashMap<>();
        for (AdCampaign c : eligible) {
            boolean manualPin = adMatchQueueRepository
                    .findByScreenIdAndCampaignId(screenId, c.getId()).stream()
                    .anyMatch(q -> Boolean.TRUE.equals(q.getPinned()));
            pinnedMap.put(c.getId(), context.pinned() || manualPin);

            int maxWeight = adCampaignCreativeRepository.findByCampaignId(c.getId()).stream()
                    .mapToInt(cc -> cc.getWeight() == null ? 0 : cc.getWeight())
                    .max().orElse(0);
            weightMap.put(c.getId(), maxWeight);
        }

        List<AdCampaign> ranked = new ArrayList<>(eligible);
        Comparator<AdCampaign> byPinnedDesc = Comparator
                .comparingInt((AdCampaign c) -> pinnedMap.getOrDefault(c.getId(), false) ? 1 : 0)
                .reversed();
        Comparator<AdCampaign> byWeightDesc = Comparator
                .comparingInt((AdCampaign c) -> weightMap.getOrDefault(c.getId(), 0))
                .reversed();
        ranked.sort(byPinnedDesc.thenComparing(byWeightDesc));

        Instant now = Instant.now();
        List<Long> orderedIds = new ArrayList<>();
        int rank = 0;
        for (AdCampaign c : ranked) {
            boolean pinned = pinnedMap.get(c.getId());
            AdMatchQueue row = AdMatchQueue.builder()
                    .screenId(screenId)
                    .campaignId(c.getId())
                    .rank(rank)
                    .pinned(pinned)
                    .nextAt(now)
                    .build();
            adMatchQueueRepository.save(row);
            orderedIds.add(c.getId());
            rank++;
        }
        log.debug("matched screen={} -> campaigns={}", screenId, orderedIds);
        return orderedIds;
    }
}
