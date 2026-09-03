package com.claw.server.domain.station;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构性解耦守护（模块四 · BC-1 / BC-2 / BC-3）。
 *
 * <p>本测试不依赖 Spring 上下文与数据库，用两种手段把"三层解耦"钉成回归红线：
 * <ol>
 *   <li><b>反射白名单</b>：三个 {@code @Transactional} 服务实际持有的 Repository 字段集合必须在
 *       各自白名单内，且不得出现"另一层的写仓储"——这是 BC-1 的结构性保证（依赖都拿不到，
 *       就不可能跨层写）。</li>
 *   <li><b>源码文本扫描</b>：对跨层"只读"引用（项目层读 stock 算可用量、结算层读 movements 算消耗），
 *       断言源码中不存在对该仓储的 {@code save/saveAll/delete/deleteById/flush} 调用——这是
 *       BC-2 / BC-3 的行为性保证（拿到了也没写）。</li>
 * </ol>
 * 任何人日后往 {@code StationProjectService} 里加一行 {@code stockRepository.save(...)}，本测试立刻变红。
 */
class StationLayerDecouplingTest {

    /** 库存层写仓储。 */
    private static final Set<String> INVENTORY_REPOS =
            Set.of("StationStockRepository", "StationInventoryMovementRepository");
    /** 项目层写仓储。 */
    private static final Set<String> PROJECT_REPOS =
            Set.of("StationProjectRepository", "StationProjectInventoryAllocRepository");
    /** 结算层写仓储。 */
    private static final Set<String> SETTLEMENT_REPOS =
            Set.of("StationSettlementRepository", "StationSettlementItemRepository");

    private static final Pattern WRITE_CALL = Pattern.compile(
            "\\b(\\w+)\\s*\\.\\s*(save|saveAll|saveAndFlush|delete|deleteAll|deleteById|deleteBy\\w+|flush)\\s*\\(");

    /* ===================== BC-1：写事务边界（反射白名单） ===================== */

    @Test
    @DisplayName("BC-1 库存层：StationInventoryService 只持有库存层仓储，不持有项目/结算层任何仓储")
    void inventoryServiceHoldsOnlyInventoryRepositories() {
        Set<String> repos = repositoryFieldTypes(StationInventoryService.class);

        assertEquals(INVENTORY_REPOS, repos,
                "库存层仓储集合必须精确等于 {StationStockRepository, StationInventoryMovementRepository}，实际=" + repos);
        assertNoneOf(repos, PROJECT_REPOS, "StationInventoryService 不得持有项目层仓储");
        assertNoneOf(repos, SETTLEMENT_REPOS, "StationInventoryService 不得持有结算层仓储");
    }

    @Test
    @DisplayName("BC-1 项目层：StationProjectService 只持有项目层仓储 + 只读 StationStockRepository，不持有 movements/结算层仓储")
    void projectServiceHoldsOnlyProjectRepositoriesPlusReadOnlyStock() {
        Set<String> repos = repositoryFieldTypes(StationProjectService.class);

        assertTrue(repos.containsAll(PROJECT_REPOS), "项目层必须持有自身两个仓储，实际=" + repos);
        // 允许多持有一个"只读"的 StationStockRepository（算可用量），写入由源码扫描测试禁止
        assertEquals(Set.of("StationProjectRepository", "StationProjectInventoryAllocRepository",
                        "StationStockRepository"), repos,
                "项目层仓储集合应为 {项目, 占用, 只读库存}，实际=" + repos);
        assertFalse(repos.contains("StationInventoryMovementRepository"),
                "项目层不得持有库存流水仓储（会造成跨层写风险）");
        assertNoneOf(repos, SETTLEMENT_REPOS, "StationProjectService 不得持有结算层仓储");
    }

    @Test
    @DisplayName("BC-1 结算层：StationSettlementService 不持有 station_stock / station_projects / alloc 任何写仓储")
    void settlementServiceHoldsNoInventoryOrProjectWriteRepositories() {
        Set<String> repos = repositoryFieldTypes(StationSettlementService.class);

        assertTrue(repos.containsAll(SETTLEMENT_REPOS), "结算层必须持有自身两个仓储，实际=" + repos);
        assertFalse(repos.contains("StationStockRepository"),
                "BC-1 违反：结算层持有 StationStockRepository，存在跨层写库存风险");
        assertNoneOf(repos, PROJECT_REPOS, "BC-1 违反：结算层不得持有项目层仓储（含 alloc）");
        // 结算层允许"只读"引用 movements / SKU / 商品 / 提成规则 / 系统配置
        assertTrue(repos.contains("StationInventoryMovementRepository"),
                "结算层需只读 movements 作为消耗数据源，实际=" + repos);
    }

    /* ===================== BC-2 / BC-3：跨层只读（源码文本扫描） ===================== */

    @Test
    @DisplayName("BC-3 项目层源码中不存在对 stockRepository 的任何写调用（分配绝不扣库存）")
    void projectServiceNeverWritesStockRepository() throws IOException {
        List<String> writes = writeCallsOnField(sourceOf("StationProjectService"), "stockRepository");
        assertTrue(writes.isEmpty(),
                "BC-3 违反：StationProjectService 出现对 station_stock 的写调用 -> " + writes);
    }

    @Test
    @DisplayName("BC-1/BC-2 结算层源码中不存在对 movements/SKU/商品/提成规则/系统配置的任何写调用（跨层只读）")
    void settlementServiceNeverWritesCrossLayerRepositories() throws IOException {
        String src = sourceOf("StationSettlementService");
        for (String field : List.of("movementRepository", "productSkuRepository", "productRepository",
                "commissionRuleRepository", "systemConfigRepository")) {
            List<String> writes = writeCallsOnField(src, field);
            assertTrue(writes.isEmpty(),
                    "BC-1/BC-2 违反：StationSettlementService 对跨层仓储 " + field + " 存在写调用 -> " + writes);
        }
    }

    @Test
    @DisplayName("BC-1 库存层源码中的写调用只落在 stockRepository / movementRepository 两个字段上")
    void inventoryServiceWritesOnlyOwnRepositories() throws IOException {
        Set<String> targets = writeTargets(sourceOf("StationInventoryService"));
        assertEquals(Set.of("stockRepository", "movementRepository"), targets,
                "库存层写目标必须只有本层两个仓储，实际=" + targets);
    }

    @Test
    @DisplayName("BC-1 项目层源码中的写调用只落在 projectRepository / allocRepository 两个字段上")
    void projectServiceWritesOnlyOwnRepositories() throws IOException {
        Set<String> targets = writeTargets(sourceOf("StationProjectService"));
        assertEquals(Set.of("projectRepository", "allocRepository"), targets,
                "项目层写目标必须只有本层两个仓储，实际=" + targets);
    }

    @Test
    @DisplayName("BC-1 结算层源码中的写调用只落在 settlementRepository / itemRepository 两个字段上")
    void settlementServiceWritesOnlyOwnRepositories() throws IOException {
        Set<String> targets = writeTargets(sourceOf("StationSettlementService"));
        assertEquals(Set.of("settlementRepository", "itemRepository"), targets,
                "结算层写目标必须只有本层两个仓储，实际=" + targets);
    }

    /* ===================== BC-2：实体层面只有 ID 引用，无 JPA 关联 ===================== */

    @Test
    @DisplayName("BC-2 三张新表实体不存在任何 JPA 关联注解（@ManyToOne/@OneToMany/@JoinColumn），跨层只有 Long ID 字段")
    void crossLayerReferencesAreIdOnly() {
        for (Class<?> entity : List.of(StationProjectInventoryAlloc.class, StationSettlementItem.class,
                StationInventoryMovement.class)) {
            for (Field f : entity.getDeclaredFields()) {
                for (java.lang.annotation.Annotation a : f.getAnnotations()) {
                    String n = a.annotationType().getSimpleName();
                    assertFalse(Set.of("ManyToOne", "OneToMany", "OneToOne", "ManyToMany", "JoinColumn",
                                    "JoinTable").contains(n),
                            "BC-2 违反：" + entity.getSimpleName() + "." + f.getName() + " 使用了 JPA 关联注解 @" + n);
                }
            }
        }
        // 跨层引用字段必须是 Long（纯 ID）
        assertEquals(Long.class, fieldType(StationProjectInventoryAlloc.class, "stationStockId"));
        assertEquals(Long.class, fieldType(StationSettlementItem.class, "refId"));
        assertEquals(Long.class, fieldType(StationInventoryMovement.class, "stationProjectId"));
    }

    /* ============================== 工具 ============================== */

    private static Set<String> repositoryFieldTypes(Class<?> service) {
        return java.util.Arrays.stream(service.getDeclaredFields())
                .map(f -> f.getType().getSimpleName())
                .filter(n -> n.endsWith("Repository"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Class<?> fieldType(Class<?> owner, String name) {
        try {
            return owner.getDeclaredField(name).getType();
        } catch (NoSuchFieldException e) {
            throw new AssertionError("字段不存在：" + owner.getSimpleName() + "." + name, e);
        }
    }

    private static void assertNoneOf(Set<String> actual, Set<String> forbidden, String message) {
        Set<String> hit = actual.stream().filter(forbidden::contains).collect(Collectors.toSet());
        assertTrue(hit.isEmpty(), message + "，命中=" + hit);
    }

    /** 读取 domain/station 下某个类的源码文本（surefire 工作目录为 backend/，同时兼容仓库根目录）。 */
    private static String sourceOf(String simpleName) throws IOException {
        String rel = "src/main/java/com/claw/server/domain/station/" + simpleName + ".java";
        for (Path base : List.of(Path.of(""), Path.of("backend"), Path.of(".."), Path.of("../backend"))) {
            Path p = base.resolve(rel);
            if (Files.exists(p)) {
                return Files.readString(p, StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("找不到源码文件：" + rel + "（cwd=" + Path.of("").toAbsolutePath() + "）");
    }

    /** 收集源码中所有"字段.写方法(" 的字段名（忽略注释行与 builder 链）。 */
    private static Set<String> writeTargets(String source) {
        Set<String> out = new LinkedHashSet<>();
        for (String line : stripComments(source).split("\n")) {
            Matcher m = WRITE_CALL.matcher(line);
            while (m.find()) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    private static List<String> writeCallsOnField(String source, String field) {
        return stripComments(source).lines()
                .filter(l -> Pattern.compile("\\b" + Pattern.quote(field)
                        + "\\s*\\.\\s*(save|saveAll|saveAndFlush|delete|deleteAll|deleteById|deleteBy\\w+|flush)\\s*\\(")
                        .matcher(l).find())
                .map(String::trim)
                .toList();
    }

    /** 去掉块注释与行注释，避免 Javadoc 里的示例文本造成误判。 */
    private static String stripComments(String source) {
        String noBlock = source.replaceAll("(?s)/\\*.*?\\*/", "");
        return noBlock.replaceAll("(?m)//.*$", "");
    }
}
