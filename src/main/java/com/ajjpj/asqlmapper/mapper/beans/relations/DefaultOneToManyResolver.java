package com.ajjpj.asqlmapper.mapper.beans.relations;

import java.sql.Connection;
import java.util.Optional;

import com.ajjpj.asqlmapper.core.common.CollectionBuildStrategy;
import com.ajjpj.asqlmapper.javabeans.BeanProperty;
import com.ajjpj.asqlmapper.mapper.beans.BeanMapping;
import com.ajjpj.asqlmapper.mapper.beans.tablename.TableNameExtractor;
import com.ajjpj.asqlmapper.mapper.schema.ForeignKeySpec;
import com.ajjpj.asqlmapper.mapper.schema.SchemaRegistry;
import com.ajjpj.asqlmapper.mapper.schema.TableMetaData;
import com.ajjpj.asqlmapper.mapper.util.BeanReflectionHelper;

public class DefaultOneToManyResolver implements OneToManyResolver {
    public OneToManySpec resolve(Connection conn, BeanMapping ownerMapping, String propertyName,
                                 TableNameExtractor tableNameExtractor, SchemaRegistry schemaRegistry,
                                 Optional<String> optDetailTable, Optional<String> optFk, Optional<String> optPk) {
        final BeanProperty toManyProp = ownerMapping.beanMetaData().beanProperties().get(propertyName);
        if (toManyProp == null) {
            throw new IllegalArgumentException(ownerMapping.beanMetaData().beanType() + " has no mapped property " + propertyName);
        }

        final Class<?> detailElementClass = BeanReflectionHelper.elementType(toManyProp.propType());

        //TODO evaluate @OneToMany

        final String detailTableName = optDetailTable.orElseGet(() -> tableNameExtractor.tableNameForBean(conn, detailElementClass, schemaRegistry));

        final ForeignKeySpec fk;

        if (optFk.isPresent()) {
            fk = new ForeignKeySpec(optFk.get(), detailTableName, ownerMapping.pkProperty().columnName(), ownerMapping.tableName());
        } else {
            final TableMetaData detailMetaData = schemaRegistry.getRequiredTableMetaData(conn, detailTableName);
            fk = detailMetaData.uniqueFkTo(ownerMapping.tableName());
        }

        final Class<?> keyType = ownerMapping.beanMetaData().getBeanPropertyForColumnName(fk.pkColumnName()).propClass();
        return new OneToManySpec(fk, detailElementClass, CollectionBuildStrategy.get(toManyProp.propClass()), keyType);
    }
}
