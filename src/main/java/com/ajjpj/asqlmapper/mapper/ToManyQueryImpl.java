package com.ajjpj.asqlmapper.mapper;

import com.ajjpj.asqlmapper.core.PrimitiveTypeRegistry;
import com.ajjpj.asqlmapper.core.RowExtractor;
import com.ajjpj.asqlmapper.core.SqlSnippet;
import com.ajjpj.asqlmapper.core.impl.SqlHelper;
import com.ajjpj.asqlmapper.mapper.provided.ProvidedProperties;
import com.ajjpj.asqlmapper.mapper.provided.ProvidedValues;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collector;


//TODO demo / test: bean (with nested to-many), scalar
class ToManyQueryImpl<K,T,R> implements ToManyQuery<K,R> {
    private final RowExtractor beanExtractor;
    private final ProvidedProperties providedProperties;

    private final Class<K> keyType;
    private final String keyColumn;
    private final Class<T> manyType;
    private final SqlSnippet sql;
    private final PrimitiveTypeRegistry primTypes;
    private final Collector<T,?,? extends R> collectorPerPk;

    ToManyQueryImpl (RowExtractor beanExtractor, ProvidedProperties providedProperties, Class<K> keyType,
                     String keyColumn, Class<T> manyType, SqlSnippet sql, PrimitiveTypeRegistry primTypes, Collector<T, ?, ? extends R> collectorPerPk) {
        this.beanExtractor = beanExtractor;
        this.providedProperties = providedProperties;
        this.keyType = keyType;
        this.keyColumn = keyColumn;
        this.manyType = manyType;
        this.sql = sql;
        this.primTypes = primTypes;
        this.collectorPerPk = collectorPerPk;

        if (providedProperties.nonEmpty() && ! (beanExtractor instanceof BeanRegistryBasedRowExtractor)) {
            throw new IllegalArgumentException("provided values are only supported for bean mappings");
        }
    }

    @Override public ProvidedValues execute (Connection conn) throws SQLException {
        final Map<K, List<T>> raw = new HashMap<>();

        try (final PreparedStatement ps = conn.prepareStatement(sql.getSql())) {
            SqlHelper.bindParameters(ps, sql.getParams(), primTypes);
            final ResultSet rs = ps.executeQuery();
            final Object memento = beanExtractor.mementoPerQuery(manyType, primTypes, rs);

            while(rs.next()) {
                final K key = primTypes.fromSql(keyType, rs.getObject(keyColumn));
                final T value;
                if (beanExtractor instanceof BeanRegistryBasedRowExtractor) {
                    value = ((BeanRegistryBasedRowExtractor) beanExtractor).fromSql(manyType, primTypes, rs, memento, providedProperties);
                }
                else {
                    value = beanExtractor.fromSql(manyType, primTypes, rs, memento);
                }
                raw.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
            }
        }

        final Map<K,R> result = new HashMap<>();
        for(Map.Entry<K,List<T>> e: raw.entrySet()) {
            final R r = e.getValue().stream().collect(collectorPerPk);
            result.put(e.getKey(), r);
        }
        return ProvidedValues.of(result);
    }

    @Override public ToManyQuery withPropertyValues (String propertyName, ProvidedValues propertyValues) {
        return new ToManyQueryImpl<>(beanExtractor, providedProperties.with(propertyName, propertyValues), keyType, keyColumn, manyType, sql, primTypes, collectorPerPk);
    }

    @Override public ToManyQuery withPropertyValues (ProvidedProperties providedProperties) {
        if (this.providedProperties.nonEmpty()) throw new IllegalArgumentException("non-empty provided properties would be overwritten"); //TODO
        return new ToManyQueryImpl<>(beanExtractor, providedProperties, keyType, keyColumn, manyType, sql, primTypes, collectorPerPk);
    }
}