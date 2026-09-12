package com.claw.server.domain.advertising;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/**
 * 广告素材（多格式/尺寸/时长 + 轻量可编字段）。
 * 对应 claw.ad_creatives（V120）。
 */
@Entity
@Table(name = "ad_creatives", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdCreative {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** IMAGE / VIDEO */
    @Column(nullable = false)
    private String type;

    @Column(name = "file_url", nullable = false)
    private String fileUrl;

    private String mime;

    @Column(name = "duration_sec")
    private Integer durationSec;

    @Column(name = "thumb_url")
    private String thumbUrl;

    private Integer width;
    private Integer height;

    @Builder.Default
    @Column(name = "white_bg")
    private Boolean whiteBg = false;

    /** UPLOAD / FROM_PRODUCT */
    @Builder.Default
    @Column(nullable = false)
    private String source = "UPLOAD";

    @Column(name = "product_ref")
    private String productRef;

    /** 标题/文案/CTA/裁剪预设（轻量可编）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String editableJson;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
