package com.claw.server;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Transient;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构守护（两层）：
 * <ul>
 *   <li>领域边界 —— 禁止跨模块直查表（对应技术文档 1.3），后期按模块拆微服务时保证拆分不返工；</li>
 *   <li>ORM 映射陷阱 —— 禁止实体字段名以大写字母结尾却不显式声明列名（真库冒烟暴露过的坑）。</li>
 * </ul>
 */
class ArchitectureBoundaryTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.claw.server");

    /** 资金域（ledger）最优先保持封闭：其他域不得直查 ledger 的表（Repository），
     *  但允许通过应用服务（AccountService/LedgerService）交互（技术文档 1.3）。 */
    @Test
    void ledgerDomainMustBeClosed() {
        noClasses().that().resideOutsideOfPackage("com.claw.server.domain.ledger..")
                .should().dependOnClassesThat(
                        resideInAPackage("com.claw.server.domain.ledger..")
                                .and(simpleNameEndingWith("Repository")))
                .because("资金域仓储只允许本域访问，跨域通过应用服务 AccountService/LedgerService 交互（技术文档 1.3）")
                .check(CLASSES);
    }

    /** 订单域不得直持资产域仓储：跨域只经 AssetService 服务接口 + AssetProvisionedEvent 事件，
     *  否则拆分微服务时资产表被订单域强耦合（技术文档 1.3 + V38 设计 §6 边界）。 */
    @Test
    void orderDomainMustNotHoldAssetRepository() {
        noClasses().that().resideInAPackage("com.claw.server.domain.order..")
                .should().dependOnClassesThat(
                        resideInAPackage("com.claw.server.domain.asset..")
                                .and(simpleNameEndingWith("Repository")))
                .because("订单域只经 AssetService 接口与领域事件与资产域交互，不得直持资产域 Repository（V38 边界）")
                .check(CLASSES);
    }

    /** 通用层不依赖任何域：common/config/infra 保持纯粹 */
    @Test
    void commonLayerMustNotDependOnDomains() {
        noClasses().that().resideInAPackage("com.claw.server.common..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.claw.server.domain..")
                .because("common 层被所有域复用，不得反向依赖")
                .check(CLASSES);
    }

    /**
     * 实体字段名不得以大写字母结尾，除非显式用 {@code @Column(name = "...")} 指定列名。
     *
     * <p><b>根因</b>：Spring Boot 默认的 {@code SpringPhysicalNamingStrategy} 做驼峰转下划线时，
     * 只在「大写字母的前后都是小写字母」处插下划线，且循环边界是 {@code i < length - 1}，
     * 因此<b>末尾是大写字母</b>的字段名永远拆不出下划线，只会被整体小写：
     * {@code radiusM → radiusm}，而 {@code tenantId → tenant_id} 完全正常。
     *
     * <p>真库（{@code ddl-auto: none} + Flyway）上列名由 SQL 迁移固定为 {@code radius_m}，
     * 隐式命名推出来的 {@code radiusm} 直接导致查询报列不存在；而 {@code local} profile 是
     * H2 + {@code ddl-auto: update}，Hibernate 按自己的（错误）规则自动建表，把洞盖住了，
     * 所以单测与本地跑永远发现不了——只有真库端到端冒烟才暴露。
     *
     * <p><b>豁免</b>（这些字段不参与列命名推导，天然安全）：
     * <ul>
     *   <li>已显式 {@code @Column(name = "...")} 指定列名的字段 —— 显式声明本身就是正确做法；</li>
     *   <li>{@code static} / {@code final} / 编译器合成（{@code synthetic}）字段 —— 不构成持久化属性；</li>
     *   <li>{@code @Transient} / {@code @Embedded} 字段 —— 前者不映射列，后者由被嵌入类型自身的
     *       字段决定列名，本规则已在被嵌入类型所在的类上独立生效。</li>
     * </ul>
     */
    @Test
    void entityFieldsMustNotEndWithUppercaseWithoutExplicitColumnName() {
        fields()
                .that().areDeclaredInClassesThat(DOMAIN_ENTITY)
                .should(new EndsWithUppercaseFieldDeclaresExplicitColumnName())
                .because("字段名以大写字母结尾时 Hibernate 隐式命名会推成全小写（radiusM → radiusm），"
                        + "与 Flyway 建表的下划线列名不一致；必须用 @Column(name = \"...\") 显式指定")
                .check(CLASSES);
    }

    /** {@code domain..} 包下带 {@code @Entity} 注解的类。 */
    private static final DescribedPredicate<JavaClass> DOMAIN_ENTITY =
            resideInAPackage("com.claw.server.domain..")
                    .and(CanBeAnnotated.Predicates.annotatedWith(Entity.class));

    /** {@code jakarta.persistence.Column} 的全限定名。 */
    private static final String COLUMN_ANNOTATION_NAME = Column.class.getName();

    /** 字段名以大写字母结尾（{@code .*[A-Z]$}）时，必须显式声明 {@code @Column(name = "...")}。 */
    private static final class EndsWithUppercaseFieldDeclaresExplicitColumnName extends ArchCondition<JavaField> {

        private static final Pattern ENDS_WITH_UPPERCASE = Pattern.compile(".*[A-Z]$");

        private EndsWithUppercaseFieldDeclaresExplicitColumnName() {
            super("以大写字母结尾时必须用 @Column(name = \"...\") 显式指定列名");
        }

        @Override
        public void check(JavaField field, ConditionEvents events) {
            if (!isMappedColumn(field)) {
                return;
            }
            if (!ENDS_WITH_UPPERCASE.matcher(field.getName()).matches()) {
                return;
            }
            if (explicitColumnName(field).isPresent()) {
                return;
            }
            String message = String.format(
                    "%s.%s 字段名以大写字母结尾且未显式声明 @Column(name = \"...\")；"
                            + "Hibernate 隐式命名会推成 %s，与 Flyway 建表的下划线列名不一致",
                    field.getOwner().getSimpleName(),
                    field.getName(),
                    field.getName().toLowerCase(Locale.ROOT));
            events.add(SimpleConditionEvent.violated(field, message));
        }
    }

    /** 该字段是否参与列命名推导（排除 static / final / synthetic / @Transient / @Embedded）。 */
    private static boolean isMappedColumn(JavaField field) {
        Set<JavaModifier> modifiers = field.getModifiers();
        if (modifiers.contains(JavaModifier.STATIC)
                || modifiers.contains(JavaModifier.FINAL)
                || modifiers.contains(JavaModifier.SYNTHETIC)
                || field.getName().contains("$")) {
            return false;
        }
        return !field.isAnnotatedWith(Transient.class) && !field.isAnnotatedWith(Embedded.class);
    }

    /** 字段上显式声明的 {@code @Column(name = ...)} 列名；没有声明（或声明为空串）时返回 empty。 */
    private static Optional<String> explicitColumnName(JavaField field) {
        for (JavaAnnotation<JavaField> annotation : field.getAnnotations()) {
            if (!COLUMN_ANNOTATION_NAME.equals(annotation.getRawType().getName())) {
                continue;
            }
            Object raw = annotation.get("name").orElse("");
            if (raw instanceof String name && !name.isBlank()) {
                return Optional.of(name);
            }
        }
        return Optional.empty();
    }
}
