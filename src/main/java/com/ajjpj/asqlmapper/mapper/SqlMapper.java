package com.ajjpj.asqlmapper.mapper;

import com.ajjpj.acollections.util.AOption;
import com.ajjpj.asqlmapper.ASqlEngine;
import com.ajjpj.asqlmapper.core.SqlSnippet;
import com.ajjpj.asqlmapper.mapper.beans.BeanMetaData;
import com.ajjpj.asqlmapper.mapper.beans.BeanProperty;
import com.ajjpj.asqlmapper.mapper.beans.BeanRegistry;

import java.sql.Connection;

import static com.ajjpj.acollections.util.AUnchecker.executeUnchecked;
import static com.ajjpj.asqlmapper.core.SqlSnippet.*;


public class SqlMapper {
    private final ASqlEngine sqlEngine;
    private final BeanRegistry beanRegistry;

    public SqlMapper (ASqlEngine sqlEngine, BeanRegistry beanRegistry) {
        this.sqlEngine = sqlEngine;
        this.beanRegistry = beanRegistry;
    }

    //TODO insert a list of objects

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
            final Object pkValue = sqlEngine.insertSingleColPk(beanMetaData.pkProperty().propType(), insertStmt, beanMetaData.pkProperty().columnMetaData().colName).executeSingle(conn);
            //noinspection unchecked
            return (T) beanMetaData.pkProperty().set(o, pkValue);
        });
    }
    private SqlSnippet insertStatement(BeanMetaData beanMetaData, Object bean) {
        final SqlSnippet into = commaSeparated(beanMetaData.beanProperties().filterNot(BeanProperty::isPrimaryKey).map(p -> sql(p.columnMetaData().colName)));
        final SqlSnippet values = params(beanMetaData.beanProperties().filterNot(BeanProperty::isPrimaryKey).map(p -> p.get(bean)));
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
            final AOption<Object> optPk = beanMetaData.pkStrategy().newPrimaryKey(conn);
            final Object withPk = optPk.fold(withoutPk, (res, el) -> beanMetaData.pkProperty().set(res, el));

            final SqlSnippet insertStmt = insertStatement(beanMetaData, withPk);

            sqlEngine.update(insertStmt).execute(conn);
            //noinspection unchecked
            return (T) withPk;
        });
    }

    public int update(Connection conn, Object bean) {
        return executeUnchecked(() -> {
            final BeanMetaData beanMetaData = beanRegistry.getMetaData(conn, bean.getClass());

            final SqlSnippet updates = commaSeparated(
                    beanMetaData.beanProperties()
                            .iterator()
                            .filterNot(p -> p.columnMetaData().isPrimaryKey)
                            .map(p -> sql(p.columnMetaData().colName + "=?", p.get(bean)))
            );

            final SqlSnippet stmt = concat(
                    sql("UPDATE " + beanMetaData.tableMetaData().tableName + " SET"),
                    updates,
                    sql("WHERE " + beanMetaData.pkProperty().columnMetaData().colName + "=?", beanMetaData.pkProperty().get(bean))
            );

            return sqlEngine.update(stmt).execute(conn);
        });
    }

    //TODO delete

    //TODO patch
}