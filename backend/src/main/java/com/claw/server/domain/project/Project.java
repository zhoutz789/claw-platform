package com.claw.server.domain.project;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 项目（对应 claw.projects）。
 *
 * <p>自引用树结构：{@code parent_id} 指向父项目（根项目 parent_id 为空），
 * {@code depth} 为层级深度（根=0），{@code sort_no} 支持同层自由上下排序。
 * 每个项目绑定一个 ledger 的 {@code accounts(account_type='PROJECT')} 账户用于独立核算，
 * {@code account_id} 在此落库，账户本身由 {@code LedgerService} 创建（跨域走服务接口）。
 */
@Entity
@Table(name = "projects", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long ownerUserId;

    @Column(nullable = false, length = 120)
    private String name;

    private Long parentId;

    @Column(nullable = false)
    @Builder.Default
    private int depth = 0;

    @Builder.Default
    private int sortNo = 0;

    private Long accountId;

    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(nullable = false)
    @Builder.Default
    private Long tenantId = 1L;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
