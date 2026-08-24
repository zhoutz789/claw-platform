package com.claw.server.domain.settings;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * 系统配置项（对应 claw.system_config，V17 新增）。
 * 平台级参数（押金额度、费率开关、风控阈值等）的键值存储，admin 可维护。
 */
@Entity
@Table(name = "system_config", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String configKey;

    @Column(columnDefinition = "text")
    private String configValue;

    private String category;

    @Column(columnDefinition = "text")
    private String description;

    /** STRING / NUMBER / BOOLEAN / JSON。 */
    @Column(nullable = false)
    @Builder.Default
    private String dataType = "STRING";

    @Column(nullable = false)
    @Builder.Default
    private Boolean editable = true;

    @Builder.Default
    private Long tenantId = 1L;

    @Builder.Default
    private Boolean deleted = false;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
