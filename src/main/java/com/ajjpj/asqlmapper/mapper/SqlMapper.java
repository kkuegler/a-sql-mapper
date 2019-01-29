package com.ajjpj.asqlmapper.mapper;

import com.ajjpj.acollections.AList;
import com.ajjpj.acollections.ASet;
import com.ajjpj.acollections.immutable.AHashSet;
import com.ajjpj.acollections.immutable.AVector;
import com.ajjpj.acollections.util.AOption;
import com.ajjpj.asqlmapper.SqlEngine;
import com.ajjpj.asqlmapper.core.RowExtractor;
import com.ajjpj.asqlmapper.core.SqlBuilder;
import com.ajjpj.asqlmapper.core.SqlSnippet;
import com.ajjpj.asqlmapper.mapper.beans.BeanProperty;
import com.ajjpj.asqlmapper.mapper.beans.BeanRegistry;
import com.ajjpj.asqlmapper.mapper.beans.TableAwareBeanMetaData;
import com.ajjpj.asqlmapper.mapper.provided.ProvidedProperties;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import static com.ajjpj.acollections.util.AUnchecker.executeUnchecked;
import static com.ajjpj.asqlmapper.core.SqlSnippet.*;


public class SqlMapper {
    private final SqlEngine sqlEngine;
    private final BeanRegistry beanRegistry;
    private final BeanRegistryBasedRowExtractor beanRowExtractor;

    public SqlMapper (SqlEngine sqlEngine, BeanRegistry beanRegistry) {
        this.beanRowExtractor = new BeanRegistryBasedRowExtractor(beanRegistry);
        this.sqlEngine = sqlEngine.withRowExtractor(beanRowExtractor);
        this.beanRegistry = beanRegistry;
    }

    public SqlEngine engine() {
        return sqlEngine;
    }

    public <T> AMapperQuery<T> query(Class<T> beanType, String sqlString, Object... params) {
        return query(beanType, sql(sqlString, params));
    }
    public <T> AMapperQuery<T> query(Class<T> beanType, SqlSnippet sql) {
        if (!beanRowExtractor.canHandle(beanType)) throw new IllegalArgumentException(beanType + " is not a mapped bean");
        return new AMapperQueryImpl<>(beanType,sql, sqlEngine.primitiveTypeRegistry(), beanRowExtractor, ProvidedProperties.empty(), sqlEngine.listeners(), engine().defaultConnectionSupplier());
    }                                                                                                         

    public SqlSnippet tableName(Class<?> beanType) {
        return tableName(engine().defaultConnection(), beanType);
    }
    public SqlSnippet tableName(Connection conn, Class<?> beanType) {
        return sql(beanRegistry.getTableAwareMetaData(conn, beanType, AOption.empty()).tableName());
    }


    //TODO query convenience (factory for SqlSnippet?): by pk, "select * from <tablename>", ...;-)

    //TODO convenience: queryForOneToMany, queryForManyToMany

    public <K,T> ToManyQuery<K, ASet<T>> queryForToManyASet(Class<T> beanType, String fkName, Class<K> fkType, SqlSnippet sql) {
        return queryForToMany(beanType, fkName, fkType, sql, AHashSet.streamCollector());
    }
    public <K,T> ToManyQuery<K, AList<T>> queryForToManyAList(Class<T> beanType, String fkName, Class<K> fkType, SqlSnippet sql) {
        return queryForToMany(beanType, fkName, fkType, sql, AVector.streamCollector());
    }
    public <K,T> ToManyQuery<K, Set<T>> queryForToManySet(Class<T> beanType, String fkName, Class<K> fkType, SqlSnippet sql) {
        return queryForToMany(beanType, fkName, fkType, sql, Collectors.toSet());
    }
    public <K,T> ToManyQuery<K, List<T>> queryForToManyList(Class<T> beanType, String fkName, Class<K> fkType, SqlSnippet sql) {
        return queryForToMany(beanType, fkName, fkType, sql, Collectors.toList());
    }
    public <K,T,R> ToManyQuery<K, R> queryForToMany(Class<T> beanType, String fkName, Class<K> fkType, SqlSnippet sql, Collector<T,?,? extends R> collectorPerPk) {
        final RowExtractor rowExtractor = engine().rowExtractorFor(beanType);
        return new ToManyQueryImpl<>(rowExtractor, ProvidedProperties.empty(), fkType, fkName, beanType, sql, engine().primitiveTypeRegistry(), collectorPerPk,
                engine().defaultConnectionSupplier());
    }

    public <T> AList<T> insertMany(List<T> os) {
        return insertMany(engine().defaultConnection(), os);
    }
    public <T> AList<T> insertMany(Connection conn, List<T> os) {
        return insertMany(conn, os, AOption.empty());
    }
    public <T> AList<T> insertMany(List<T> os, String tableName) {
        return insertMany(engine().defaultConnection(), os, tableName);
    }
    public <T> AList<T> insertMany(Connection conn, List<T> os, String tableName) {
        return insertMany(conn, os, AOption.of(tableName));
    }
    private <T> AList<T> insertMany(Connection conn, List<T> os, AOption<String> providedTableName) {
        if(os.isEmpty()) return AList.empty();

        final TableAwareBeanMetaData beanMetaData = beanRegistry.getTableAwareMetaData(conn, os.get(0).getClass(), providedTableName);
        if (beanMetaData.pkStrategy().isAutoIncrement()) {
            return insertManyAutoGenerated(conn, beanMetaData, os, providedTableName);
        }
        else {
            return insertManyProvidingPk(conn, beanMetaData, os, providedTableName);
        }
    }
    private <T> AVector<T> insertManyAutoGenerated(Connection conn, TableAwareBeanMetaData beanMetaData, List<T> os, AOption<String> providedTableName) {
        return executeUnchecked(() -> {
            final BeanProperty pkProperty = beanMetaData.pkProperty().orNull();

            final SqlBuilder builder = SqlSnippet.builder();
            boolean first = true;
            for (T o: os) {
                if (beanRegistry.getTableAwareMetaData(conn, o.getClass(), providedTableName) != beanMetaData) throw new IllegalArgumentException("multi-insert only for beans of the same type");
                appendInsertFragmentForElement(beanMetaData, builder, first, o, false);
                first = false;
            }

            if (pkProperty != null) {
                final List<?> pkValues = sqlEngine.insertSingleColPk(pkProperty.propType(), builder.build(), pkProperty.columnName()).executeMulti(conn);
                if (pkValues.size() != os.size()) throw new IllegalStateException("inserting " + os.size() + " rows returned " + pkValues.size() + " - mismatch");

                final AVector.Builder<T> result = AVector.builder();
                for (int i=0; i<os.size(); i++) {
                    //noinspection unchecked
                    result.add((T) pkProperty.set(os.get(i), pkValues.get(i)));
                }
                return result.build();
            }
            else {
                sqlEngine.update(builder.build()).execute(conn);
                return AVector.from(os);
            }
        });
    }

    private <T> void appendInsertFragmentForElement (TableAwareBeanMetaData beanMetaData, SqlBuilder builder, boolean first, T o, boolean includePkColumn) {
        if (first) {
            builder.append(insertStatement(beanMetaData, o, includePkColumn));
        }
        else {
            builder
                    .append(",(")
                    .append(params(beanMetaData.writableBeanProperties(includePkColumn).map(p -> p.get(o))))
                    .append(")")
            ;
        }
    }

    private <T> AVector<T> insertManyProvidingPk(Connection conn, TableAwareBeanMetaData beanMetaData, List<T> os, AOption<String> providedTablename) {
        return executeUnchecked(() -> {
            final AVector.Builder<T> result = AVector.builder();

            final SqlBuilder builder = SqlSnippet.builder();
            boolean first = true;
            for (Object withoutPk: os) {
                if (beanRegistry.getTableAwareMetaData(conn, withoutPk.getClass(), providedTablename) != beanMetaData) throw new IllegalArgumentException("multi-insert only for beans of the same type");
                final AOption<Object> optPk = beanMetaData.pkStrategy().newPrimaryKey(conn);
                final Object withPk;
                if (beanMetaData.pkProperty().isDefined()) {
                    withPk = optPk.fold(withoutPk, (res, el) -> beanMetaData.pkProperty().get().set(res, el));
                }
                else {
                    withPk = withoutPk;
                }
                appendInsertFragmentForElement(beanMetaData, builder, first, withPk, true);
                first=false;
            }

            sqlEngine.update(builder.build()).execute(conn);
            return result.build();
        });
    }

    public <T> T insert(T o) {
        return insert(engine().defaultConnection(), o);
    }
    public <T> T insert(Connection conn, T o) {
        return insert(conn, o, AOption.empty());
    }
    public <T> T insert(T o, String tableName) {
        return insert(engine().defaultConnection(), o, tableName);
    }
    public <T> T insert(Connection conn, T o, String tableName) {
        return insert(conn, o, AOption.of(tableName));
    }
    private <T> T insert(Connection conn, T o, AOption<String> providedTableName) {
        final TableAwareBeanMetaData beanMetaData = beanRegistry.getTableAwareMetaData(conn, o.getClass(), providedTableName);
        if (beanMetaData.pkStrategy().isAutoIncrement()) {
            return insertAutoGenerated(conn, o, providedTableName);
        }
        else {
            return insertProvidingPk(conn, o, providedTableName);
        }
    }
    private <T> T insertAutoGenerated(Connection conn, T o, AOption<String> providedTableName) {
        return executeUnchecked(() -> {
            final TableAwareBeanMetaData beanMetaData = beanRegistry.getTableAwareMetaData(conn, o.getClass(), providedTableName);
            final SqlSnippet insertStmt = insertStatement(beanMetaData, o, false);

            if (beanMetaData.pkProperty().isDefined()) {
                final BeanProperty pkProperty = beanMetaData.pkProperty().get();
                final Object pkValue = sqlEngine.insertSingleColPk(pkProperty.propType(), insertStmt, pkProperty.columnName()).executeSingle(conn);
                //noinspection unchecked
                return (T) pkProperty.set(o, pkValue);
            }
            else {
                sqlEngine.update(insertStmt).execute(conn);
                return o;
            }
        });
    }
    private SqlSnippet insertStatement(TableAwareBeanMetaData beanMetaData, Object bean, boolean withPk) {
        final SqlSnippet into = commaSeparated(
                beanMetaData
                        .writableBeanProperties(withPk)
                        .flatMap(p -> p.columnMetaData().map(m -> sql(m.colName())))
        );

        final SqlSnippet values = params(
                beanMetaData
                        .writableBeanProperties(withPk)
                        .flatMap(p -> p.columnMetaData().map(m -> p.get(bean)))
        );
        return concat(
                sql("INSERT INTO " + beanMetaData.tableName() + "("),
                into,
                sql(") VALUES ("),
                values,
                sql(")")
        );
    }
    private <T> T insertProvidingPk(Connection conn, Object beanWithoutPk, AOption<String> providedTableName) {
        return executeUnchecked(() -> {
            final TableAwareBeanMetaData beanMetaData = beanRegistry.getTableAwareMetaData(conn, beanWithoutPk.getClass(), providedTableName);

            final Object beanWithPk;
            if (beanMetaData.pkProperty().isDefined()) {
                final AOption<Object> optPk = beanMetaData.pkStrategy().newPrimaryKey(conn);
                beanWithPk = optPk.fold(beanWithoutPk, (res, el) -> beanMetaData.pkProperty().get().set(res, el));
            }
            else {
                beanWithPk = beanWithoutPk;
            }

            final SqlSnippet insertStmt = insertStatement(beanMetaData, beanWithPk, true);

            sqlEngine.update(insertStmt).execute(conn);
            //noinspection unchecked
            return (T) beanWithPk;
        });
    }

    public boolean update(Object bean) {
        return update(engine().defaultConnection(), bean);
    }
    public boolean update(Connection conn, Object bean) {
        return update(conn, bean, AOption.empty());
    }
    public boolean update(Object bean, String tableName) {
        return update(engine().defaultConnection(), bean, tableName);
    }
    public boolean update(Connection conn, Object bean, String tableName) {
        return update(conn, bean, AOption.of(tableName));
    }
    private boolean update(Connection conn, Object bean, AOption<String> providedTableName) {
        return executeUnchecked(() -> {
            final TableAwareBeanMetaData beanMetaData = beanRegistry.getTableAwareMetaData(conn, bean.getClass(), providedTableName);
            if (beanMetaData.pkProperty().isEmpty()) throw new IllegalArgumentException("update requires a bean to contain the table's primary key");

            final SqlSnippet updates = commaSeparated(
                    beanMetaData
                            .writableBeanProperties(false)
                            .flatMap(p -> p.columnMetaData().map(m -> sql(m.colName() + "=?", p.get(bean))))
            );

            final SqlSnippet stmt = concat(
                    sql("UPDATE " + beanMetaData.tableName() + " SET"),
                    updates,
                    sql("WHERE " + beanMetaData.pkProperty().get().columnName() + "=?", beanMetaData.pkProperty().get().get(bean))
            );

            return sqlEngine.update(stmt).execute(conn) == 1;
        });
    }

    public boolean delete(Object bean) {
        return delete(engine().defaultConnection(), bean);
    }
    public boolean delete(Connection conn, Object bean) {
        return delete(conn, bean, AOption.empty());
    }
    public boolean delete(Object bean, String tableName) {
        return delete(engine().defaultConnection(), bean, tableName);
    }
    public boolean delete(Connection conn, Object bean, String tableName) {
        return delete(conn, bean, AOption.of(tableName));
    }
    private boolean delete(Connection conn, Object bean, AOption<String> providedTableName) {
        final TableAwareBeanMetaData beanMetaData = beanRegistry.getTableAwareMetaData(conn, bean.getClass(), providedTableName);
        final BeanProperty pkProperty = beanMetaData.pkProperty().orElseThrow(() -> new IllegalArgumentException("bean type " + bean.getClass() + " has no defined primary key"));

        return executeUnchecked(() ->
            sqlEngine.update("DELETE FROM " + beanMetaData.tableName() + " WHERE " + pkProperty.columnName() + "=?", pkProperty.get(bean)).execute(conn) == 1
        );
    }
    public boolean delete(Class<?> beanType, Object pk) {
        return delete(engine().defaultConnection(), beanType, pk);
    }
    public boolean delete(Connection conn, Class<?> beanType, Object pk) {
        return delete(conn, beanType, pk, AOption.empty());
    }
    public boolean delete(Class<?> beanType, Object pk, String tableName) {
        return delete(engine().defaultConnection(), beanType, pk, tableName);
    }
    public boolean delete(Connection conn, Class<?> beanType, Object pk, String tableName) {
        return delete(conn, beanType, pk, AOption.of(tableName));
    }
    private boolean delete(Connection conn, Class<?> beanType, Object pk, AOption<String> providedTableName) {
        final TableAwareBeanMetaData beanMetaData = beanRegistry.getTableAwareMetaData(conn, beanType, providedTableName);
        final BeanProperty pkProperty = beanMetaData.pkProperty().orElseThrow(() -> new IllegalArgumentException("bean type " + beanType + " has no defined primary key"));

        return executeUnchecked(() ->
                sqlEngine.update("DELETE FROM " + beanMetaData.tableName() + " WHERE " + pkProperty.columnName() + "=?", pk).execute(conn) == 1
        );
    }

    public boolean patch(Class<?> beanType, Object pk, Map<String,Object> newValues) {
        return patch(engine().defaultConnection(), beanType, pk, newValues);
    }
    public boolean patch(Connection conn, Class<?> beanType, Object pk, Map<String,Object> newValues) {
        return patch(conn, beanType, pk, newValues, AOption.empty());
    }
    public boolean patch(Class<?> beanType, Object pk, Map<String,Object> newValues, String tableName) {
        return patch(engine().defaultConnection(), beanType, pk, newValues, tableName);
    }
    public boolean patch(Connection conn, Class<?> beanType, Object pk, Map<String,Object> newValues, String tableName) {
        return patch(conn, beanType, pk, newValues, AOption.of(tableName));
    }
    private boolean patch(Connection conn, Class<?> beanType, Object pk, Map<String,Object> newValues, AOption<String> providedTableName) {
        final TableAwareBeanMetaData beanMetaData = beanRegistry.getTableAwareMetaData(conn, beanType, providedTableName);
        final BeanProperty pkProperty = beanMetaData.pkProperty().orElseThrow(() -> new IllegalArgumentException("bean type " + beanType + " has no defined primary key"));

        //TODO to-one / foreign keys

        final SqlBuilder builder = new SqlBuilder();
        boolean first = true;

        builder.append("UPDATE " + beanMetaData.tableName() + " SET");

        for (String propName: newValues.keySet()) {
            final AOption<BeanProperty> optProp = beanMetaData.beanProperties().find(p -> p.name().equals(propName));
            if (optProp.isEmpty()) continue;

            final BeanProperty prop = optProp.get();
            if (prop.columnMetaData() == null) continue;

            if (first) {
                first = false;
            }
            else {
                builder.append(",");
            }
            builder.append(prop.columnName() + "=?", newValues.get(propName));
        }

        builder.append("WHERE " + pkProperty.columnName() + "=?", pk);

        return executeUnchecked(() ->
            sqlEngine.update(builder.build()).execute(conn) == 1
        );
    }
}
