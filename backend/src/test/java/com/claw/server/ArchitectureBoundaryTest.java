package com.claw.server;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.conditions.ArchConditions.haveSimpleNameEndingWith;
import static com.tngtech.archunit.lang.conditions.ArchConditions.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 领域边界守护（对应技术文档 1.3：禁止跨模块直查表）。
 * 后期按模块拆微服务时，这些规则保证拆分不返工。
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
                                .and(haveSimpleNameEndingWith("Repository")))
                .because("资金域仓储只允许本域访问，跨域通过应用服务 AccountService/LedgerService 交互（技术文档 1.3）")
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
}
