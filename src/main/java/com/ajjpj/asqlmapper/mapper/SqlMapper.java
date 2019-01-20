package com.ajjpj.asqlmapper.mapper;

import com.ajjpj.acollections.AList;
import com.ajjpj.acollections.ASet;
import com.ajjpj.acollections.immutable.AHashSet;
import com.ajjpj.acollections.immutable.AVector;
import com.ajjpj.acollections.util.AOption;
import com.ajjpj.asqlmapper.ASqlEngine;
import com.ajjpj.asqlmapper.core.RowExtractor;
import com.ajjpj.asqlmapper.core.SqlBuilder;
import com.ajjpj.asqlmapper.core.SqlSnippet;
import com.ajjpj.asqlmapper.mapper.beans.BeanMetaData;
import com.ajjpj.asqlmapper.mapper.beans.BeanProperty;
import com.ajjpj.asqlmapper.mapper.beans.BeanRegistry;
import com.ajjpj.asqlmapper.mapper.provided.ProvidedProperties;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import static com.ajjpj.acollections.util.AUnchecker.executeUnchecked;
import static com.ajjpj.asqlmapper.core.SqlSnippet.*;


public class SqlMapper {
    private final ASqlEngine sqlEngine;
    private final BeanRegistry beanRegistry;
    private final BeanRegistryBasedRowExtractor beanRowExtractor;

    public SqlMapper (ASqlEngine sqlEngine, BeanRegistry beanRegistry, DataSource ds) {
        this.beanRowExtractor = new BeanRegistryBasedRowExtractor(ds, beanRegistry);
        this.sqlEngine = sqlEngine.withRowExtractor(beanRowExtractor);
        this.beanRegistry = beanRegistry;
    }

    public ASqlEngine engine() {
        return sqlEngine;
    }

    public <T> AMapperQuery<T> query(Class<T> beanType, String sqlString, Object... params) {
        return query(beanType, sql(sqlString, params));
    }
    public <T> AMapperQuery<T> query(Class<T> beanType, SqlSnippet sql) {
        if (!beanRowExtractor.canHandle(beanType)) throw new IllegalArgumentException(beanType + " is not a mapped bean");
        return new AMapperQueryImpl<>(beanType,sql, sqlEngine.primitiveTypeRegistry(), beanRowExtractor, ProvidedProperties.empty());
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
        return new ToManyQueryImpl<>(rowExtractor, ProvidedProperties.empty(), fkType, fkName, beanType, sql, engine().primitiveTypeRegistry(), collectorPerPk);
    }

    public <T> AList<T> insertMany(Connection conn, List<T> os) {
        if(os.isEmpty()) return AList.empty();

        final BeanMetaData beanMetaData = beanRegistry.getMetaData(conn, os.get(0).getClass());
        if (beanMetaData.pkStrategy().isAutoIncrement()) {
            return insertManyAutoGenerated(conn, beanMetaData, os);
        }
        else {
            return insertManyProvidingPk(conn, beanMetaData, os);
        }
    }
    private <T> AVector<T> insertManyAutoGenerated(Connection conn, BeanMetaData beanMetaData, List<T> os) {
        return executeUnchecked(() -> {
            final BeanProperty pkProperty = beanMetaData.pkProperty().orNull();

            final SqlBuilder builder = SqlSnippet.builder();
            boolean first = true;
            for (T o: os) {
                if (beanRegistry.getMetaData(conn, o.getClass()) != beanMetaData) throw new IllegalArgumentException("multi-insert only for beans of the same type");
                first = appendInsertFragmentForElement(beanMetaData, builder, first, o);
            }

            if (pkProperty != null) {
                final List<?> pkValues = sqlEngine.insertSingleColPk(pkProperty.propType(), builder.build(), pkProperty.columnMetaData().colName).executeMulti(conn);
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

    private <T> boolean appendInsertFragmentForElement (BeanMetaData beanMetaData, SqlBuilder builder, boolean first, T o) {
        if (first) {
            builder.append(insertStatement(beanMetaData, o));
            first = false;
        }
        else {
            builder
                    .append(",(")
                    .append(params(beanMetaData.insertedBeanProperties().map(p -> p.get(o))))
                    .append(")")
            ;
        }
        return first;
    }

    private <T> AVector<T> insertManyProvidingPk(Connection conn, BeanMetaData beanMetaData, List<T> os) {
        return executeUnchecked(() -> {
            final AVector.Builder<T> result = AVector.builder();

            final SqlBuilder builder = SqlSnippet.builder();
            boolean first = true;
            for (Object withoutPk: os) {
                if (beanRegistry.getMetaData(conn, withoutPk.getClass()) != beanMetaData) throw new IllegalArgumentException("multi-insert only for beans of the same type");
                final AOption<Object> optPk = beanMetaData.pkStrategy().newPrimaryKey(conn);
                final Object withPk;
                if (beanMetaData.pkProperty().isDefined()) {
                    withPk = optPk.fold(withoutPk, (res, el) -> beanMetaData.pkProperty().get().set(res, el));
                }
                else {
                    withPk = withoutPk;
                }
                first = appendInsertFragmentForElement(beanMetaData, builder, first, withPk);
            }

            sqlEngine.update(builder.build()).execute(conn);
            return result.build();
        });
    }

    public <T> T insert(Connection conn, T o) {
        final BeanMetaData beanMetaData = beanRegistry.getMetaData(conn, o.getClass());
        if (beanMetaData.pkStrategy().isAutoIncrement()) {
            return insertAutoGenerated(conn, o);
        }
        else {
            return insertProvidingPk(conn, o);
        }
    }
    private <T> T insertAutoGenerated(Connection conn, T o) {
        return executeUnchecked(() -> {
            final BeanMetaData beanMetaData = beanRegistry.getMetaData(conn, o.getClass());
            final SqlSnippet insertStmt = insertStatement(beanMetaData, o);

            if (beanMetaData.pkProperty().isDefined()) {
                final BeanProperty pkProperty = beanMetaData.pkProperty().get();
                final Object pkValue = sqlEngine.insertSingleColPk(pkProperty.propType(), insertStmt, pkProperty.columnMetaData().colName).executeSingle(conn);
                //noinspection unchecked
                return (T) pkProperty.set(o, pkValue);
            }
            else {
                sqlEngine.update(insertStmt).execute(conn);
                return o;
            }
        });
    }
    private SqlSnippet insertStatement(BeanMetaData beanMetaData, Object bean) {
        final SqlSnippet into = commaSeparated(
                beanMetaData
                        .insertedBeanProperties()
                        .map(p -> sql(p.columnMetaData().colName))
        );
        final SqlSnippet values = params(
                beanMetaData
                        .insertedBeanProperties()
                        .map(p -> p.get(bean))
        );
        return concat(
                sql("INSERT INTO " + beanMetaData.tableMetaData().tableName + "("),
                into,
                sql(") VALUES ("),
                values,
                sql(")")
        );
    }
    private <T> T insertProvidingPk(Connection conn, Object withoutPk) {
        return executeUnchecked(() -> {
            final BeanMetaData beanMetaData = beanRegistry.getMetaData(conn, withoutPk.getClass());

            final Object withPk;
            if (beanMetaData.pkProperty().isDefined()) {
                final AOption<Object> optPk = beanMetaData.pkStrategy().newPrimaryKey(conn);
                withPk = optPk.fold(withoutPk, (res, el) -> beanMetaData.pkProperty().get().set(res, el));
            }
            else {
                withPk = withoutPk;
            }

            final SqlSnippet insertStmt = insertStatement(beanMetaData, withPk);

            sqlEngine.update(insertStmt).execute(conn);
            //noinspection unchecked
            return (T) withPk;
        });
    }

    public boolean update(Connection conn, Object bean) {
        return executeUnchecked(() -> {
            final BeanMetaData beanMetaData = beanRegistry.getMetaData(conn, bean.getClass());
            if (beanMetaData.pkProperty().isEmpty()) throw new IllegalArgumentException("update requires a bean to contain the table's primary key");

            final SqlSnippet updates = commaSeparated(
                    beanMetaData.beanProperties()
                            .iterator()
                            .filterNot(p -> p.columnMetaData().isPrimaryKey)
                            .map(p -> sql(p.columnMetaData().colName + "=?", p.get(bean)))
            );

            final SqlSnippet stmt = concat(
                    sql("UPDATE " + beanMetaData.tableMetaData().tableName + " SET"),
                    updates,
                    sql("WHERE " + beanMetaData.pkProperty().get().columnMetaData().colName + "=?", beanMetaData.pkProperty().get().get(bean))
            );

            return sqlEngine.update(stmt).execute(conn) == 1;
        });
    }

    public boolean delete(Connection conn, Object bean) {
        final BeanMetaData beanMetaData = beanRegistry.getMetaData(conn, bean.getClass());
        final BeanProperty pkProperty = beanMetaData.pkProperty().orElseThrow(() -> new IllegalArgumentException("bean type " + bean.getClass() + " has no defined primary key"));

        return executeUnchecked(() ->
            sqlEngine.update("DELETE FROM " + beanMetaData.tableMetaData().tableName + " WHERE " + pkProperty.columnMetaData().colName + "=?", pkProperty.get(bean)).execute(conn) == 1
        );
    }
    public boolean delete(Connection conn, Class<?> beanType, Object pk) {
        final BeanMetaData beanMetaData = beanRegistry.getMetaData(conn, beanType);
        final BeanProperty pkProperty = beanMetaData.pkProperty().orElseThrow(() -> new IllegalArgumentException("bean type " + beanType + " has no defined primary key"));

        return executeUnchecked(() ->
                sqlEngine.update("DELETE FROM " + beanMetaData.tableMetaData().tableName + " WHERE " + pkProperty.columnMetaData().colName + "=?", pk).execute(conn) == 1
        );
    }

    public boolean patch(Connection conn, Class<?> beanType, Object pk, Map<String,Object> newValues) {
        final BeanMetaData beanMetaData = beanRegistry.getMetaData(conn, beanType);
        final BeanProperty pkProperty = beanMetaData.pkProperty().orElseThrow(() -> new IllegalArgumentException("bean type " + beanType + " has no defined primary key"));

        //TODO to-one / foreign keys

        final SqlBuilder builder = new SqlBuilder();
        boolean first = true;

        builder.append("UPDATE " + beanMetaData.tableMetaData().tableName + " SET");

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
            builder.append(prop.columnMetaData().colName + "=?", newValues.get(propName));
        }

        builder.append("WHERE " + pkProperty.columnMetaData().colName + "=?", pk);

        return executeUnchecked(() ->
            sqlEngine.update(builder.build()).execute(conn) == 1
        );
    }
}
