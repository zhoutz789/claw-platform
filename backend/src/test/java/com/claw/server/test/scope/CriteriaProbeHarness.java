package com.claw.server.test.scope;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.Database;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 数据范围单元测试用的 Criteria 执行夹具（仅测试作用域，权限通电 P1-T03 决策③a）。
 *
 * <p>用内存 H2 引导一个<b>只含探针实体</b>（{@link ScopeProbe} / {@link ScopeProbeUser}）的
 * {@link EntityManagerFactory}，不加载业务实体、不连外部数据库、不启动 Spring 容器，
 * 因此足够轻量可作为纯单元测试使用。
 *
 * <p><b>为什么要真实执行而不是断言 Predicate.toString()：</b>
 * Hibernate 6 的 SQM 谓词节点（如 {@code SqmComparisonPredicate}）并未覆写
 * {@code toString()}，其输出形如 {@code ...SqmComparisonPredicate@1f2a3b4c}，
 * 不含列名/操作符等可断言的信息，且该输出属未公开的内部实现细节、跨小版本易变。
 * 故本夹具改用两条<b>更强</b>的证据：
 * <ol>
 *   <li><b>语义证据</b>——把查询真正跑在 H2 上，断言「究竟返回了哪几行」；</li>
 *   <li><b>SQL 证据</b>——通过 Hibernate {@link StatementInspector} 抓取真实下发的 SQL，
 *       断言其中包含期望的列名与操作符标记（{@code ownerId} / {@code in} / {@code like} …）。</li>
 * </ol>
 * 两者结合既覆盖了「标记是否出现」，也证明了「过滤语义是否正确」。
 */
public final class CriteriaProbeHarness implements AutoCloseable {

    private final LocalContainerEntityManagerFactoryBean factoryBean;
    private final EntityManagerFactory entityManagerFactory;
    private final RecordingStatementInspector inspector;

    private CriteriaProbeHarness(LocalContainerEntityManagerFactoryBean factoryBean,
                                 EntityManagerFactory entityManagerFactory,
                                 RecordingStatementInspector inspector) {
        this.factoryBean = factoryBean;
        this.entityManagerFactory = entityManagerFactory;
        this.inspector = inspector;
    }

    /**
     * 引导一套独立的内存 H2 + Hibernate 环境（库名带随机后缀，避免并行用例互相干扰）。
     *
     * @return 已就绪的夹具，使用完须 {@link #close()}
     */
    public static CriteriaProbeHarness bootstrap() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:datascope-probe-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");

        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        vendorAdapter.setDatabase(Database.H2);
        vendorAdapter.setGenerateDdl(true);

        RecordingStatementInspector inspector = new RecordingStatementInspector();
        Map<String, Object> jpaProperties = new HashMap<>();
        jpaProperties.put("hibernate.dialect", H2Dialect.class.getName());
        jpaProperties.put("hibernate.hbm2ddl.auto", "create-drop");
        jpaProperties.put("hibernate.show_sql", "false");
        // Hibernate 6：AvailableSettings.STATEMENT_INSPECTOR，可直接注入实例
        jpaProperties.put("hibernate.session_factory.statement_inspector", inspector);

        LocalContainerEntityManagerFactoryBean factoryBean = new LocalContainerEntityManagerFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setPackagesToScan("com.claw.server.test.scope");
        factoryBean.setJpaVendorAdapter(vendorAdapter);
        factoryBean.setJpaPropertyMap(jpaProperties);
        factoryBean.afterPropertiesSet();

        EntityManagerFactory emf = Objects.requireNonNull(factoryBean.getObject(),
                "探针 EntityManagerFactory 引导失败");
        return new CriteriaProbeHarness(factoryBean, emf, inspector);
    }

    /** 暴露 CriteriaBuilder，供需要自行拼装谓词的用例使用。 */
    public CriteriaBuilder criteriaBuilder() {
        return entityManagerFactory.getCriteriaBuilder();
    }

    /** 写入探针业务行种子数据。 */
    public void seedProbes(List<ScopeProbe> rows) {
        persistAll(rows);
    }

    /** 写入探针「归属人 / 部门」行种子数据（供子查询分支使用）。 */
    public void seedUsers(List<ScopeProbeUser> rows) {
        persistAll(rows);
    }

    private void persistAll(List<?> rows) {
        EntityManager em = entityManagerFactory.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            for (Object row : rows) {
                em.persist(row);
            }
            tx.commit();
        } catch (RuntimeException e) {
            if (tx.isActive()) {
                tx.rollback();
            }
            throw e;
        } finally {
            em.close();
        }
    }

    /**
     * 把 Specification 翻译成谓词、挂到查询上并<b>真实执行</b>。
     *
     * @param spec 待验证的 Specification
     * @return 命中的主键（升序）与本次真实下发的 SQL
     */
    public ProbeQuery execute(Specification<ScopeProbe> spec) {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            CriteriaQuery<ScopeProbe> query = cb.createQuery(ScopeProbe.class);
            Root<ScopeProbe> root = query.from(ScopeProbe.class);
            Predicate predicate = spec.toPredicate(root, query, cb);
            if (predicate != null) {
                query.where(predicate);
            }
            query.orderBy(cb.asc(root.get("id")));

            inspector.reset();
            List<ScopeProbe> rows = em.createQuery(query).getResultList();
            List<Long> ids = rows.stream().map(ScopeProbe::getId).toList();
            return new ProbeQuery(ids, inspector.lastStatement(), predicate);
        } finally {
            em.close();
        }
    }

    /**
     * 以探针实体为载体执行一个「面向其它实体类型」的 Specification。
     *
     * <p>JVM 泛型擦除后 {@code Specification<User>} 与 {@code Specification<ScopeProbe>} 同构，
     * 只要该 Specification 访问的属性名在探针实体上存在（如 {@code departmentId}），即可安全求值。
     * 用于验证控制器/服务真实构造出的 Specification（经 ArgumentCaptor 捕获）。
     *
     * @param spec 任意实体类型的 Specification
     * @param <T>  原始实体类型
     * @return 命中的探针主键与真实 SQL
     */
    @SuppressWarnings("unchecked")
    public <T> ProbeQuery executeAsProbe(Specification<T> spec) {
        return execute((Specification<ScopeProbe>) (Specification<?>) spec);
    }

    /**
     * 只翻译不执行，返回谓词本身，供需要做结构性断言的场景
     * （例如判定 ALL 是否退化为空的 {@code conjunction()}）。
     *
     * @param spec 待翻译的 Specification
     * @param <T>  原始实体类型
     * @return 翻译出的谓词，可能为 {@code null}
     */
    @SuppressWarnings("unchecked")
    public <T> Predicate translate(Specification<T> spec) {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            CriteriaQuery<ScopeProbe> query = cb.createQuery(ScopeProbe.class);
            Root<ScopeProbe> root = query.from(ScopeProbe.class);
            return ((Specification<ScopeProbe>) (Specification<?>) spec).toPredicate(root, query, cb);
        } finally {
            em.close();
        }
    }

    /** 关闭 EntityManagerFactory 与底层内存库。 */
    @Override
    public void close() {
        factoryBean.destroy();
    }

    /**
     * 一次探针查询的结果。
     *
     * @param ids       命中的主键（升序）
     * @param sql       Hibernate 真实下发的 SQL（无语句时为空串）
     * @param predicate 翻译出的谓词（可能为 {@code null}）
     */
    public record ProbeQuery(List<Long> ids, String sql, Predicate predicate) {
    }

    /** 记录 Hibernate 下发 SQL 的拦截器。 */
    private static final class RecordingStatementInspector implements StatementInspector {

        private final List<String> statements = Collections.synchronizedList(new ArrayList<>());

        @Override
        public String inspect(String sql) {
            statements.add(sql);
            return sql;
        }

        void reset() {
            statements.clear();
        }

        String lastStatement() {
            synchronized (statements) {
                return statements.isEmpty() ? "" : statements.get(statements.size() - 1);
            }
        }
    }
}
