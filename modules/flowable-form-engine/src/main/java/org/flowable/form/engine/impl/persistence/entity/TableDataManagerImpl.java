/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.flowable.form.engine.impl.persistence.entity;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.session.RowBounds;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.management.TableMetaData;
import org.flowable.common.engine.api.management.TablePage;
import org.flowable.common.engine.impl.db.DbSqlSession;
import org.flowable.common.engine.impl.persistence.entity.Entity;
import org.flowable.form.engine.FormEngineConfiguration;
import org.flowable.form.engine.impl.TablePageQueryImpl;
import org.flowable.form.engine.impl.persistence.AbstractManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Tom Baeyens
 */
public class TableDataManagerImpl extends AbstractManager implements TableDataManager {

    public TableDataManagerImpl(FormEngineConfiguration formEngineConfiguration) {
        super(formEngineConfiguration);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(TableDataManagerImpl.class);

    public static Map<Class<?>, String> apiTypeToTableNameMap = new HashMap<>();
    public static Map<Class<? extends Entity>, String> entityToTableNameMap = new HashMap<>();

    static {

        // Form module
        entityToTableNameMap.put(FormDeploymentEntity.class, "ACT_FO_FORM_DEPLOYMENT");
        entityToTableNameMap.put(FormDefinitionEntity.class, "ACT_FO_FORM_DEFINITION");
        entityToTableNameMap.put(FormResourceEntity.class, "ACT_FO_FORM_RESOURCE");
        entityToTableNameMap.put(FormInstanceEntity.class, "ACT_FO_FORM_INSTANCE");
    }

    protected DbSqlSession getDbSqlSession() {
        return getSession(DbSqlSession.class);
    }

    @Override
    public Map<String, Long> getTableCount() {
        Map<String, Long> tableCount = new HashMap<>();
        try {
            for (String tableName : getTablesPresentInDatabase()) {
                tableCount.put(tableName, getTableCount(tableName));
            }
            LOGGER.debug("Number of rows per flowable table: {}", tableCount);
        } catch (Exception e) {
            throw new FlowableException("couldn't get table counts", e);
        }
        return tableCount;
    }

    @Override
    public List<String> getTablesPresentInDatabase() {
        List<String> tableNames = new ArrayList<>();
        try {
            Connection connection = getDbSqlSession().getSqlSession().getConnection();
            DatabaseMetaData databaseMetaData = connection.getMetaData();
            LOGGER.debug("retrieving flowable tables from jdbc metadata");
            String databaseTablePrefix = getDbSqlSession().getDbSqlSessionFactory().getDatabaseTablePrefix();
            String tableNameFilter = databaseTablePrefix + "ACT_%";
            if ("postgres".equals(getDbSqlSession().getDbSqlSessionFactory().getDatabaseType())
                || "cockroachdb".equals(getDbSqlSession().getDbSqlSessionFactory().getDatabaseType())) {
                tableNameFilter = databaseTablePrefix + "act_%";
            }
            if ("oracle".equals(getDbSqlSession().getDbSqlSessionFactory().getDatabaseType())) {
                tableNameFilter = databaseTablePrefix + "ACT" + databaseMetaData.getSearchStringEscape() + "_%";
            }

            String catalog = null;
            if (getFormEngineConfiguration().getDatabaseCatalog() != null && getFormEngineConfiguration().getDatabaseCatalog().length() > 0) {
                catalog = getFormEngineConfiguration().getDatabaseCatalog();
            }

            String schema = null;
            if (getFormEngineConfiguration().getDatabaseSchema() != null && getFormEngineConfiguration().getDatabaseSchema().length() > 0) {
                if ("oracle".equals(getDbSqlSession().getDbSqlSessionFactory().getDatabaseType())) {
                    schema = getFormEngineConfiguration().getDatabaseSchema().toUpperCase();
                } else {
                    schema = getFormEngineConfiguration().getDatabaseSchema();
                }
            }

            try (ResultSet tables = databaseMetaData.getTables(catalog, schema, tableNameFilter, DbSqlSession.JDBC_METADATA_TABLE_TYPES)) {
                while (tables.next()) {
                    String tableName = tables.getString("TABLE_NAME");
                    tableName = tableName.toUpperCase();
                    tableNames.add(tableName);
                    LOGGER.debug("retrieved flowable table name {}", tableName);
                }
            }
        } catch (Exception e) {
            throw new FlowableException("couldn't get flowable table names using metadata: " + e.getMessage(), e);
        }
        return tableNames;
    }

    protected long getTableCount(String tableName) {
        LOGGER.debug("selecting table count for {}", tableName);
        Long count = (Long) getDbSqlSession().selectOne("org.flowable.form.engine.impl.TablePageMap.selectTableCount", Collections.singletonMap("tableName", tableName));
        return count;
    }

    @Override
    @SuppressWarnings("unchecked")
    public TablePage getTablePage(TablePageQueryImpl tablePageQuery, int firstResult, int maxResults) {

        TablePage tablePage = new TablePage();

        @SuppressWarnings("rawtypes")
        List tableData = getDbSqlSession().getSqlSession().selectList("selectTableData", tablePageQuery, new RowBounds(firstResult, maxResults));

        tablePage.setTableName(tablePageQuery.getTableName());
        tablePage.setTotal(getTableCount(tablePageQuery.getTableName()));
        tablePage.setRows((List<Map<String, Object>>) tableData);
        tablePage.setFirstResult(firstResult);

        return tablePage;
    }

    @Override
    public String getTableName(Class<?> entityClass, boolean withPrefix) {
        String databaseTablePrefix = getDbSqlSession().getDbSqlSessionFactory().getDatabaseTablePrefix();
        String tableName = null;

        if (Entity.class.isAssignableFrom(entityClass)) {
            tableName = entityToTableNameMap.get(entityClass);
        } else {
            tableName = apiTypeToTableNameMap.get(entityClass);
        }
        if (withPrefix) {
            return databaseTablePrefix + tableName;
        } else {
            return tableName;
        }
    }

    @Override
    public TableMetaData getTableMetaData(String tableName) {
        TableMetaData result = new TableMetaData();
        try {
            result.setTableName(tableName);
            DatabaseMetaData metaData = getDbSqlSession().getSqlSession().getConnection().getMetaData();

            if ("postgres".equals(getDbSqlSession().getDbSqlSessionFactory().getDatabaseType())
                    || "cockroachdb".equals(getDbSqlSession().getDbSqlSessionFactory().getDatabaseType())) {
                tableName = tableName.toLowerCase();
            }

            String catalog = null;
            if (getFormEngineConfiguration().getDatabaseCatalog() != null && getFormEngineConfiguration().getDatabaseCatalog().length() > 0) {
                catalog = getFormEngineConfiguration().getDatabaseCatalog();
            }

            String schema = null;
            if (getFormEngineConfiguration().getDatabaseSchema() != null && getFormEngineConfiguration().getDatabaseSchema().length() > 0) {
                if ("oracle".equals(getDbSqlSession().getDbSqlSessionFactory().getDatabaseType())) {
                    schema = getFormEngineConfiguration().getDatabaseSchema().toUpperCase();
                } else {
                    schema = getFormEngineConfiguration().getDatabaseSchema();
                }
            }

            ResultSet resultSet = metaData.getColumns(catalog, schema, tableName, null);
            while (resultSet.next()) {
                boolean wrongSchema = false;
                if (schema != null && schema.length() > 0) {
                    for (int i = 0; i < resultSet.getMetaData().getColumnCount(); i++) {
                        String columnName = resultSet.getMetaData().getColumnName(i + 1);
                        if ("TABLE_SCHEM".equalsIgnoreCase(columnName) || "TABLE_SCHEMA".equalsIgnoreCase(columnName)) {
                            if (!schema.equalsIgnoreCase(resultSet.getString(resultSet.getMetaData().getColumnName(i + 1)))) {
                                wrongSchema = true;
                            }
                            break;
                        }
                    }
                }

                if (!wrongSchema) {
                    String name = resultSet.getString("COLUMN_NAME").toUpperCase();
                    String type = resultSet.getString("TYPE_NAME").toUpperCase();
                    result.addColumnMetaData(name, type);
                }
            }

        } catch (SQLException e) {
            throw new FlowableException("Could not retrieve database metadata: " + e.getMessage());
        }

        if (result.getColumnNames().isEmpty()) {
            // According to API, when a table doesn't exist, null should be returned
            result = null;
        }
        return result;
    }

}
