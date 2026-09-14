package com.claw.server.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 差错差异类型（对应 suspense_entry.diff_type，V131 定义；设计 §8）。
 *
 * <ul>
 *   <li>{@code CHANNEL_EXTRA}：通道有、账本无（漏单）→ 补录进 SUSPENSE 待确认；</li>
 *   <li>{@code BOOK_EXTRA}：账本有、通道无（假单）→ 挂起人工核查；</li>
 *   <li>{@code AMOUNT_MISMATCH}：金额不符 → 差额进 SUSPENSE 追因；</li>
 *   <li>{@code FX_DIFF}：汇兑差异 → 单独科目月度结转；</li>
 *   <li>{@code UNMATCHED}：未匹配项。</li>
 * </ul>
 *
 * <p>{@link #isValid(String)} 用于 {@code SuspenseService.record} 入参校验，避免落库非法差异类型。
 */
public enum SuspenseDiffType {

    CHANNEL_EXTRA,
    BOOK_EXTRA,
    AMOUNT_MISMATCH,
    FX_DIFF,
    UNMATCHED;

    /** 全部合法差异类型的名字集合（大写）。 */
    private static final Set<String> ALL = Arrays.stream(values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    /**
     * 判断给定差异类型名是否合法（大小写敏感，须与枚举名一致）。
     *
     * @param diffType 差异类型名（通常来自 {@link #name()}）
     * @return 合法返回 {@code true}
     */
    public static boolean isValid(String diffType) {
        return diffType != null && ALL.contains(diffType);
    }
}
