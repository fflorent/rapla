/*--------------------------------------------------------------------------*
 | Copyright (C) 2014 Christopher Kohlhaas                                  |
 |                                                                          |
 | This program is free software; you can redistribute it and/or modify     |
 | it under the terms of the GNU General Public License as published by the |
 | Free Software Foundation. A copy of the license has been included with   |
 | these distribution in the COPYING file, if not go to www.fsf.org         |
 |                                                                          |
 | As a special exception, you are granted the permissions to link this     |
 | program with every library, which license fulfills the Open Source       |
 | Definition as published by the Open Source Initiative (OSI).             |
 *--------------------------------------------------------------------------*/
package org.rapla.storage.dbsql;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.locks.Lock;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.sql.DataSource;

import org.rapla.ConnectInfo;
import org.rapla.RaplaResources;
import org.rapla.components.util.Cancelable;
import org.rapla.components.util.Command;
import org.rapla.components.util.CommandScheduler;
import org.rapla.components.util.xml.RaplaNonValidatedInput;
import org.rapla.entities.Entity;
import org.rapla.entities.RaplaType;
import org.rapla.entities.User;
import org.rapla.entities.configuration.Preferences;
import org.rapla.entities.domain.permission.PermissionExtension;
import org.rapla.entities.dynamictype.DynamicType;
import org.rapla.entities.extensionpoints.FunctionFactory;
import org.rapla.entities.internal.ModifiableTimestamp;
import org.rapla.entities.internal.UserImpl;
import org.rapla.entities.storage.RefEntity;
import org.rapla.framework.RaplaException;
import org.rapla.framework.RaplaLocale;
import org.rapla.framework.internal.ConfigTools;
import org.rapla.framework.logger.Logger;
import org.rapla.storage.CachableStorageOperator;
import org.rapla.storage.CachableStorageOperatorCommand;
import org.rapla.storage.IdCreator;
import org.rapla.storage.ImportExportManager;
import org.rapla.storage.LocalCache;
import org.rapla.storage.PreferencePatch;
import org.rapla.storage.RaplaSecurityException;
import org.rapla.storage.UpdateEvent;
import org.rapla.storage.impl.EntityStore;
import org.rapla.storage.impl.server.EntityHistory;
import org.rapla.storage.impl.server.EntityHistory.HistoryEntry;
import org.rapla.storage.impl.server.LocalAbstractCachableOperator;
import org.rapla.storage.xml.IOContext;
import org.rapla.storage.xml.RaplaDefaultXMLContext;
import org.rapla.storage.xml.RaplaXMLContextException;

/** This Operator is used to store the data in a SQL-DBMS.*/
@Singleton public class DBOperator extends LocalAbstractCachableOperator
{
    //protected String datasourceName;
    protected boolean isConnected;
    Properties dbProperties = new Properties();
    boolean bSupportsTransactions = false;
    boolean hsqldb = false;

    //private String backupEncoding;
    //private String backupFileName;

    DataSource lookup;

    private String connectionName;
    Cancelable cleanupOldLocks;
    Cancelable refreshTask;
    Provider<ImportExportManager> importExportManager;
    CommandScheduler scheduler;

    @Inject public DBOperator(Logger logger, RaplaResources i18n, RaplaLocale locale, final CommandScheduler scheduler,
            Map<String, FunctionFactory> functionFactoryMap, Provider<ImportExportManager> importExportManager, DataSource dataSource,
            Set<PermissionExtension> permissionExtensions)
    {
        super(logger, i18n, locale, scheduler, functionFactoryMap, permissionExtensions);
        lookup = dataSource;
        this.scheduler = scheduler;
        this.importExportManager = importExportManager;
        //        String backupFile = config.getChild("backup").getValue("");
        //        if (backupFile != null)
        //        	backupFileName = ContextTools.resolveContext( backupFile, context);
        //
        //        backupEncoding = config.getChild( "encoding" ).getValue( "utf-8" );

        //        datasourceName = config.getChild("datasource").getValue(null);
        //        // dont use datasource (we have to configure a driver )
        //        if ( datasourceName == null)
        //        {
        //            throw new RaplaException("Could not instantiate DB. Datasource not configured ");
        //        }
        //        else
        //        {
        //	        try {
        //	        	lookupDeprecated  = ContextTools.resolveContextObject(datasourceName, context );
        //	        } catch (RaplaXMLContextException ex) {
        //	        	throw new RaplaDBException("Datasource " + datasourceName + " not found");
        //	        }
        //        }

    }

    public void scheduleCleanupAndRefresh(final CommandScheduler scheduler)
    {
        final int delay = 15000;
        cleanupOldLocks = scheduler.schedule(new Command()
        {
            @Override public void execute() throws Exception
            {
                final Lock writeLock = writeLock();
                try (final Connection connection = createConnection())
                {
                    final RaplaDefaultXMLContext context = createOutputContext(cache);
                    final RaplaSQL raplaSQL = new RaplaSQL(context);
                    raplaSQL.cleanupOldLocks(connection);
                    connection.commit();
                }
                catch (Throwable t)
                {
                    DBOperator.this.logger.info("Could not release old locks");
                }
                finally
                {
                    unlock(writeLock);
                }
                scheduler.schedule(this, delay);
            }
        }, delay);
        refreshTask = scheduler.schedule(new Command()
        {
            @Override public void execute() throws Exception
            {
                try
                {
                    refresh();
                }
                catch (Throwable t)
                {
                    DBOperator.this.logger.info("Could not refresh data");
                }
                scheduler.schedule(this, delay);
            }
        }, delay);
    }

    public boolean supportsActiveMonitoring()
    {
        return true;
    }

    public String getConnectionName()
    {
        if (connectionName != null)
        {
            return connectionName;
        }
        //    	if ( datasourceName != null)
        //    	{
        //    	    return datasourceName;
        //    	}
        return "not configured";
    }

    public Connection createConnection() throws RaplaException
    {
        boolean withTransactionSupport = true;
        return createConnection(withTransactionSupport);
    }

    public ImportExportManager getImportExportManager()
    {
        return importExportManager.get();
    }

    public Connection createConnection(boolean withTransactionSupport) throws RaplaException
    {
        return createConnection(withTransactionSupport, 0);
    }

    private Connection createConnection(final boolean withTransactionSupport, final int count) throws RaplaException
    {
        Connection connection = null;
        try
        {
            //datasource lookupDeprecated
            Object source = lookup;
            //        	if ( lookupDeprecated instanceof String)
            //        	{
            //        		InitialContext ctx = new InitialContext();
            //        		source  = ctx.lookupDeprecated("java:comp/env/"+ lookupDeprecated);
            //        	}
            //        	else
            //        	{
            //        		source = lookupDeprecated;
            //        	}

            ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
            try
            {
                try
                {
                    Thread.currentThread().setContextClassLoader(source.getClass().getClassLoader());
                }
                catch (Exception ex)
                {
                }
                try
                {
                    DataSource ds = (DataSource) source;
                    connection = ds.getConnection();
                }
                catch (ClassCastException ex)
                {
                    String text = "Datasource object " + source.getClass() + " does not implement a datasource interface.";
                    getLogger().error(text);
                    throw new RaplaDBException(text);
                }
            }
            finally
            {
                try
                {
                    Thread.currentThread().setContextClassLoader(contextClassLoader);
                }
                catch (Exception ex)
                {
                }
            }
            if (withTransactionSupport)
            {
                bSupportsTransactions = connection.getMetaData().supportsTransactions();
                if (bSupportsTransactions)
                {
                    connection.setAutoCommit(false);
                }
                else
                {
                    getLogger().warn("No Transaction support");
                }
            }
            else
            {
                connection.setAutoCommit(true);
            }
            //connection.createStatement().execute( "ALTER TABLE RESOURCE RENAME TO RAPLA_RESOURCE");
            // 		     connection.commit();
            return connection;
        }
        catch (Throwable ex)
        {
            if (connection != null)
            {
                close(connection);
            }
            if (ex instanceof SQLException && count < 2)
            {
                getLogger().warn("Getting error " + ex.getMessage() + ". Retrying.");
                return createConnection(withTransactionSupport, count + 1);
            }
            if (ex instanceof RaplaDBException)
            {
                throw (RaplaDBException) ex;
            }
            throw new RaplaDBException("DB-Connection aborted", ex);
        }
    }

    @Override
    synchronized public void connect() throws RaplaException
    {
        if (!isConnected())
        {
            getLogger().debug("Connecting: " + getConnectionName());
            loadData();

            initIndizes();
            isConnected = true;
            getLogger().debug("Connected");
            scheduleCleanupAndRefresh(scheduler);
        }
        /*
        if (connectInfo != null)
        {
            final String username = connectInfo.getUsername();
            final UserImpl user = cache.getUser(username);
            if (user == null)
            {
                throw new RaplaSecurityException("User " + username + " not found!");
            }
            return user;
        }
        else
        {
            return null;
        }*/
    }

    public boolean isConnected()
    {
        return isConnected;
    }

    @Override public void refresh() throws RaplaException
    {
        final Lock writeLock = writeLock();
        try (Connection c = createConnection())
        {
            refreshWithoutLock(c);
        }
        catch (Throwable e)
        {
            Date lastUpdated = getLastUpdated();
            logger.error("Error updating model from DB. Last success was at " + lastUpdated, e);
        }
        finally
        {
            unlock(writeLock);
        }
    }

    private void refreshWithoutLock(Connection c) throws SQLException
    {
        final EntityStore entityStore = new EntityStore(cache, cache.getSuperCategory());
        final RaplaSQL raplaSQLInput = new RaplaSQL(createInputContext(entityStore, DBOperator.this));
        Date lastUpdated = getLastUpdated();
        Date connectionTime = raplaSQLInput.getLastUpdated(c);

        if (!connectionTime.after(lastUpdated))
        {
            return;
        }
        final Collection<String> allIds = raplaSQLInput.update(c, lastUpdated, connectionTime);
        Collection<Entity> toStore = new LinkedHashSet<Entity>();
        Set<String> toRemove = new HashSet<>();
        List<PreferencePatch> patches = raplaSQLInput.getPatches(c, lastUpdated);
        for (String id : allIds)
        {
            final HistoryEntry before = history.getLastChangedUntil(id, connectionTime);
            if (before.isDelete())
            {
                toRemove.add(before.getId());
            }
            else
            {
                final Entity entity = history.getEntity(before);
                setResolver(Collections.singleton(entity));
                toStore.add(entity);
            }
        }
        refresh(lastUpdated, connectionTime, toStore, patches, toRemove);
        return;
    }

    synchronized public void disconnect() throws RaplaException
    {
        if (cleanupOldLocks != null)
        {
            cleanupOldLocks.cancel();
            cleanupOldLocks = null;
        }
        if (refreshTask != null)
        {
            refreshTask.cancel();
            refreshTask = null;
        }
        if (!isConnected())
            return;
        //        backupData();
        getLogger().info("Disconnecting: " + getConnectionName());

        cache.clearAll();
        //idTable.setCache( cache );
        history.clear();

        // HSQLDB Special
        if (hsqldb)
        {
            String sql = "SHUTDOWN COMPACT";
            try
            {
                Connection connection = createConnection();
                Statement statement = connection.createStatement();
                statement.execute(sql);
                statement.close();
            }
            catch (SQLException ex)
            {
                throw new RaplaException(ex);
            }
        }
        isConnected = false;
        getLogger().info("Disconnected");
    }

    public final void loadData() throws RaplaException
    {

        Connection c = null;
        Lock writeLock = writeLock();
        try
        {
            c = createConnection();
            connectionName = c.getMetaData().getURL();
            getLogger().info("Using datasource " + c.getMetaData().getDatabaseProductName() + ": " + connectionName);
            if (upgradeDatabase(c))
            {
                close(c);
                c = null;
                c = createConnection();
            }
            cache.clearAll();
            addInternalTypes(cache);
            loadData(c, cache);

            if (getLogger().isDebugEnabled())
                getLogger().debug("Entities contextualized");

            if (getLogger().isDebugEnabled())
                getLogger().debug("All ConfigurationReferences resolved");
        }
        catch (RaplaException ex)
        {
            throw ex;
        }
        catch (Exception ex)
        {
            throw new RaplaException(ex);
        }
        finally
        {
            unlock(writeLock);
            close(c);
            c = null;
        }
    }

    @SuppressWarnings("deprecation") private boolean upgradeDatabase(Connection c) throws SQLException, RaplaException, RaplaXMLContextException
    {
        Map<String, TableDef> schema = loadDBSchema(c);
        TableDef dynamicTypeDef = schema.get("DYNAMIC_TYPE");
        boolean empty = false;
        int oldIdColumnCount = 0;
        int unpatchedTables = 0;

        if (dynamicTypeDef != null)
        {
            PreparedStatement prepareStatement = null;
            ResultSet set = null;
            try
            {
                prepareStatement = c.prepareStatement("select * from DYNAMIC_TYPE");
                set = prepareStatement.executeQuery();
                empty = !set.next();
            }
            finally
            {
                if (set != null)
                {
                    set.close();
                }
                if (prepareStatement != null)
                {
                    prepareStatement.close();
                }
            }

            {
                org.rapla.storage.dbsql.pre18.RaplaPre18SQL raplaSQLOutput = new org.rapla.storage.dbsql.pre18.RaplaPre18SQL(createOutputContext(cache));
                Map<String, String> idColumnMap = raplaSQLOutput.getIdColumns();
                oldIdColumnCount = idColumnMap.size();
                for (Map.Entry<String, String> entry : idColumnMap.entrySet())
                {
                    String table = entry.getKey();
                    String idColumnName = entry.getValue();
                    TableDef tableDef = schema.get(table);
                    if (tableDef != null)
                    {
                        ColumnDef idColumn = tableDef.getColumn(idColumnName);
                        if (idColumn == null)
                        {
                            throw new RaplaException("Id column not found");
                        }
                        if (idColumn.isIntType())
                        {
                            unpatchedTables++;
                            //                        else if ( type.toLowerCase().contains("varchar"))
                            //                        {
                            //                            patchedTables++;
                            //                        }
                        }
                    }
                }
            }
        }
        else
        {
            empty = true;
        }
        if (!empty && (unpatchedTables == oldIdColumnCount && unpatchedTables > 0))
        {
            getLogger().warn("Old database schema detected. Initializing conversion!");
            org.rapla.storage.dbsql.pre18.RaplaPre18SQL raplaSQLOutput = new org.rapla.storage.dbsql.pre18.RaplaPre18SQL(createOutputContext(cache));
            raplaSQLOutput.createOrUpdateIfNecessary(c, schema);

            ImportExportManager manager = importExportManager.get();
            CachableStorageOperator sourceOperator = manager.getSource();
            if (sourceOperator == this)
            {
                throw new RaplaException("Can't export old db data, because no data export is set.");
            }
            LocalCache cache = new LocalCache(permissionController);
            cache.clearAll();
            addInternalTypes(cache);
            loadOldData(c, cache);

            getLogger().info("Old database loaded in memory. Now exporting to xml: " + sourceOperator);
            sourceOperator.saveData(cache, "1.1");
            getLogger().info("XML export done.");

            //close( c);
        }

        if (empty || unpatchedTables > 0)
        {
            ImportExportManager manager = importExportManager.get();
            CachableStorageOperator sourceOperator = manager.getSource();
            if (sourceOperator == this)
            {
                throw new RaplaException("Can't import, because db is configured as source.");
            }
            if (unpatchedTables > 0)
            {
                getLogger().info("Reading data from xml.");
            }
            else
            {
                getLogger().warn("Empty database. Importing data from " + sourceOperator);
            }
            sourceOperator.connect();
            if (unpatchedTables > 0)
            {
                org.rapla.storage.dbsql.pre18.RaplaPre18SQL raplaSQLOutput = new org.rapla.storage.dbsql.pre18.RaplaPre18SQL(createOutputContext(cache));
                getLogger().warn("Dropping database tables and reimport from " + sourceOperator);
                raplaSQLOutput.dropAll(c);
                // we need to load the new schema after dropping
                schema = loadDBSchema(c);
            }
            {
                RaplaSQL raplaSQLOutput = new RaplaSQL(createOutputContext(cache));
                raplaSQLOutput.createOrUpdateIfNecessary(c, schema);
            }
            close(c);
            c = null;
            c = createConnection();
            final Connection conn = c;
            sourceOperator.runWithReadLock(new CachableStorageOperatorCommand()
            {

                @Override public void execute(LocalCache cache) throws RaplaException
                {
                    try
                    {
                        saveData(conn, cache);
                    }
                    catch (SQLException ex)
                    {
                        throw new RaplaException(ex.getMessage(), ex);
                    }
                }
            });
            return true;
        }
        else
        {
            // Normal Database upgrade
            RaplaSQL raplaSQLOutput = new RaplaSQL(createOutputContext(cache));
            raplaSQLOutput.createOrUpdateIfNecessary(c, schema);
        }
        return false;
    }

    protected void updateLastChangedUser(UpdateEvent evt) throws RaplaException
    {
        String userId = evt.getUserId();
        User lastChangedBy = (userId != null) ? resolve(userId, User.class) : null;

        for (Entity e : evt.getStoreObjects())
        {
            if (e instanceof ModifiableTimestamp)
            {
                ModifiableTimestamp modifiableTimestamp = (ModifiableTimestamp) e;
                modifiableTimestamp.setLastChangedBy(lastChangedBy);
            }
        }
    }

    private Map<String, TableDef> loadDBSchema(Connection c) throws SQLException
    {
        Map<String, TableDef> tableMap = new LinkedHashMap<String, TableDef>();
        List<String> catalogList = new ArrayList<String>();
        DatabaseMetaData metaData = c.getMetaData();
        {
            ResultSet set = metaData.getCatalogs();
            try
            {
                while (set.next())
                {
                    String name = set.getString("TABLE_CAT");
                    catalogList.add(name);
                }
            }
            finally
            {
                set.close();
            }
        }
        List<String> schemaList = new ArrayList<String>();
        {
            ResultSet set = metaData.getSchemas();
            try
            {
                while (set.next())
                {
                    String name = set.getString("TABLE_SCHEM");
                    String cat = set.getString("TABLE_CATALOG");
                    schemaList.add(name);
                    if (cat != null)
                    {
                        catalogList.add(name);
                    }
                }
            }
            finally
            {
                set.close();
            }
        }

        if (catalogList.isEmpty())
        {
            catalogList.add(null);
        }
        Map<String, Set<String>> tables = new LinkedHashMap<String, Set<String>>();
        for (String cat : catalogList)
        {
            LinkedHashSet<String> tableSet = new LinkedHashSet<String>();
            String[] types = new String[] { "TABLE" };
            tables.put(cat, tableSet);
            {
                ResultSet set = metaData.getTables(cat, null, null, types);
                try
                {
                    while (set.next())
                    {
                        String name = set.getString("TABLE_NAME");
                        tableSet.add(name);
                    }
                }
                finally
                {
                    set.close();
                }
            }
        }
        for (String cat : catalogList)
        {
            Set<String> tableNameSet = tables.get(cat);
            for (String tableName : tableNameSet)
            {
                ResultSet set = metaData.getColumns(null, null, tableName, null);
                try
                {
                    while (set.next())
                    {
                        String table = set.getString("TABLE_NAME").toUpperCase(Locale.ENGLISH);
                        TableDef tableDef = tableMap.get(table);
                        if (tableDef == null)
                        {
                            tableDef = new TableDef(table);
                            tableMap.put(table, tableDef);
                        }
                        ColumnDef columnDef = new ColumnDef(set);
                        tableDef.addColumn(columnDef);
                    }
                }
                finally
                {
                    set.close();
                }
            }
        }
        return tableMap;
    }

    public void dispatch(UpdateEvent evt) throws RaplaException
    {
        Lock writeLock = writeLock();
        try
        {
            //Date since = lastUpdated;
            preprocessEventStorage(evt);
            updateLastChangedUser(evt);
            Collection<Entity> storeObjects = evt.getStoreObjects();
            List<PreferencePatch> preferencePatches = evt.getPreferencePatches();
            Collection<String> removeObjects = evt.getRemoveIds();
            if (storeObjects.isEmpty() && preferencePatches.isEmpty() && removeObjects.isEmpty())
            {
                return;
            }
            Connection connection = createConnection();
            try
            {
                dbStore(storeObjects, preferencePatches, removeObjects, connection);
                try
                {
                    refreshWithoutLock(connection);
                }
                catch (SQLException e)
                {
                    getLogger().error("Could not load update from db. Will be loaded afterwards", e);
                }
            }
            finally
            {
                close(connection);
            }
        }
        finally
        {
            unlock(writeLock);
        }
        // TODO check if still needed
        //fireStorageUpdated(result);
    }

    private void dbStore(Collection<Entity> storeObjects, List<PreferencePatch> preferencePatches, Collection<String> removeObjects, Connection connection)
    {
        RaplaSQL raplaSQLOutput = new RaplaSQL(createOutputContext(cache));
        final LinkedHashSet<String> ids = new LinkedHashSet<String>();
        for (Entity entity : storeObjects)
        {
            ids.add(entity.getId());
        }
        ids.addAll(removeObjects);
        final boolean needsGlobalLock = containsDynamicType(ids);
        try
        {
            if (needsGlobalLock)
            {
                raplaSQLOutput.getGlobalLock(connection);
            }
            else
            {
                for (PreferencePatch patch : preferencePatches)
                {
                    String userId = patch.getUserId();
                    if (userId == null)
                    {
                        userId = Preferences.SYSTEM_PREFERENCES_ID;
                    }
                    ids.add(userId);
                }
                raplaSQLOutput.getLocks(connection, ids);
            }
            Date connectionTimestamp = raplaSQLOutput.getDatabaseTimestamp(connection);
            for (String id : removeObjects)
            {
                Entity entity = cache.get(id);
                if (entity != null)
                {
                    raplaSQLOutput.remove(connection, entity, connectionTimestamp);
                }
            }
            raplaSQLOutput.store(connection, storeObjects, connectionTimestamp);
            raplaSQLOutput.storePatches(connection, preferencePatches, connectionTimestamp);
            if (bSupportsTransactions)
            {
                getLogger().debug("Commiting");
                connection.commit();
            }
            //            refreshWithoutLock(connection);
        }
        catch (Exception ex)
        {
            try
            {
                if (bSupportsTransactions)
                {
                    connection.rollback();
                    getLogger().error("Doing rollback for: " + ex.getMessage());
                    throw new RaplaDBException(getI18n().getString("error.rollback"), ex);
                }
                else
                {
                    String message = getI18n().getString("error.no_rollback");
                    getLogger().error(message);
                    forceDisconnect();
                    throw new RaplaDBException(message, ex);
                }
            }
            catch (SQLException sqlEx)
            {
                String message = "Unrecoverable error while storing";
                getLogger().error(message, sqlEx);
                forceDisconnect();
                throw new RaplaDBException(message, sqlEx);
            }
        }
        finally
        {
            try
            {
                if (needsGlobalLock)
                {
                    raplaSQLOutput.removeGlobalLock(connection);
                }
                else
                {
                    raplaSQLOutput.removeLocks(connection, ids);
                }
                if (connection.getMetaData().supportsTransactions())
                {
                    connection.commit();
                }
            }
            catch (Exception ex)
            {
                getLogger().error("Could noe remove locks. They will be removed during next cleanup. ", ex);
            }
        }
    }

    private boolean containsDynamicType(Set<String> ids)
    {
        for (String id : ids)
        {
            final Entity entity = tryResolve(id);
            if (entity != null && entity.getRaplaType() == DynamicType.TYPE)
            {
                return true;
            }
        }
        return false;
    }

    @Override protected void removeConflictsFromDatabase(Collection<String> disabledConflicts)
    {
        super.removeConflictsFromDatabase(disabledConflicts);
        if (disabledConflicts.isEmpty())
        {
            return;
        }
        Collection<Entity> storeObjects = Collections.emptyList();
        List<PreferencePatch> preferencePatches = Collections.emptyList();
        Collection<String> removeObjects = new ArrayList<String>();
        for (String id : disabledConflicts)
        {
            removeObjects.add(id);
        }
        try (Connection connection = createConnection())
        {
            dbStore(storeObjects, preferencePatches, removeObjects, connection);
        }
        catch (Exception ex)
        {
            getLogger().warn("disabled conflicts could not be removed from database due to ", ex);
        }

    }

    public void removeAll() throws RaplaException
    {
        Connection connection = createConnection();
        try
        {
            RaplaSQL raplaSQLOutput = new RaplaSQL(createOutputContext(cache));
            raplaSQLOutput.removeAll(connection);
            connection.commit();
            // do something here
            getLogger().info("DB cleared");
        }
        catch (SQLException ex)
        {
            throw new RaplaException(ex);
        }
        finally
        {
            close(connection);
        }
    }

    public synchronized void saveData(LocalCache cache, String version) throws RaplaException
    {
        Connection connection = createConnection();
        try
        {
            Map<String, TableDef> schema = loadDBSchema(connection);
            RaplaSQL raplaSQLOutput = new RaplaSQL(createOutputContext(cache));
            raplaSQLOutput.createOrUpdateIfNecessary(connection, schema);
            saveData(connection, cache);
        }
        catch (SQLException ex)
        {
            throw new RaplaException(ex);
        }
        finally
        {
            close(connection);
        }
    }

    protected void saveData(Connection connection, LocalCache cache) throws RaplaException, SQLException
    {
        String connectionName = getConnectionName();
        getLogger().info("Importing Data into " + connectionName);
        RaplaSQL raplaSQLOutput = new RaplaSQL(createOutputContext(cache));

        //		if (dropOldTables)
        //		{
        //		    getLogger().info("Droping all data from " + connectionName);
        //            raplaSQLOutput.dropAndRecreate( connection );
        //		}
        //		else
        {
            getLogger().info("Deleting all old Data from " + connectionName);
            raplaSQLOutput.removeAll(connection);
        }
        getLogger().info("Inserting new Data into " + connectionName);
        raplaSQLOutput.createAll(connection);
        if (!connection.getAutoCommit())
        {
            connection.commit();
        }
        // do something here
        getLogger().info("Import complete for " + connectionName);
    }

    private void close(Connection connection)
    {

        if (connection == null)
        {
            return;
        }
        try
        {
            if (!connection.isClosed())
            {
                getLogger().debug("Closing " + connection);
                connection.close();
            }
        }
        catch (SQLException e)
        {
            getLogger().error("Can't close connection to database ", e);
        }
    }

    @Override public Date getHistoryValidStart()
    {
        final Date date = new Date(getLastUpdated().getTime() - HISTORY_DURATION);
        return date;
    }
    
    private Date loadInitialLastUpdateFromDb(Connection connection) throws SQLException
    {
        final RaplaDefaultXMLContext createOutputContext = createOutputContext(cache);
        final RaplaSQL raplaSQL = new RaplaSQL(createOutputContext);
        final Date lastUpdated = raplaSQL.getLastUpdated(connection);
        return lastUpdated;
    }

    protected void loadData(Connection connection, LocalCache cache) throws RaplaException, SQLException
    {
        final Date lastUpdated = loadInitialLastUpdateFromDb(connection);
        setLastUpdated( lastUpdated );
        setConnectStart( lastUpdated );
        EntityStore entityStore = new EntityStore(cache, cache.getSuperCategory());
        final RaplaDefaultXMLContext inputContext = createInputContext(entityStore, this);
        RaplaSQL raplaSQLInput = new RaplaSQL(inputContext);
        raplaSQLInput.loadAll(connection);
        Collection<Entity> list = entityStore.getList();
        cache.putAll(list);
        resolveInitial(list, this);
        removeInconsistentEntities(cache, list);
        Collection<Entity> migratedTemplates = migrateTemplates();
        cache.putAll(migratedTemplates);
        List<PreferencePatch> preferencePatches = Collections.emptyList();
        Collection<String> removeObjects = Collections.emptyList();
        dbStore(migratedTemplates, preferencePatches, removeObjects, connection);
        // It is important to do the read only later because some resolve might involve write to referenced objects
        for (Entity entity : list)
        {
            ((RefEntity) entity).setReadOnly();
        }
        for (Entity entity : migratedTemplates)
        {
            ((RefEntity) entity).setReadOnly();
        }
        cache.getSuperCategory().setReadOnly();
        for (User user : cache.getUsers())
        {
            String id = user.getId();
            String password = entityStore.getPassword(id);
            cache.putPassword(id, password);
        }
        processPermissionGroups();
    }

    @SuppressWarnings("deprecation") protected void loadOldData(Connection connection, LocalCache cache) throws RaplaException, SQLException
    {
        EntityStore entityStore = new EntityStore(cache, cache.getSuperCategory());
        IdCreator idCreator = new IdCreator()
        {

            @Override public String createId(RaplaType type, String seed) throws RaplaException
            {
                String id = org.rapla.storage.OldIdMapping.getId(type, seed);
                return id;
            }

            @Override public String createId(RaplaType raplaType) throws RaplaException
            {
                throw new RaplaException("Can't create new ids in " + getClass().getName() + " this class is import only for old data ");
            }
        };
        RaplaDefaultXMLContext inputContext = createInputContext(entityStore, idCreator);

        org.rapla.storage.dbsql.pre18.RaplaPre18SQL raplaSQLInput = new org.rapla.storage.dbsql.pre18.RaplaPre18SQL(inputContext);
        raplaSQLInput.loadAll(connection);
        Collection<Entity> list = entityStore.getList();
        cache.putAll(list);
        resolveInitial(list, cache);
        // It is important to do the read only later because some resolve might involve write to referenced objects
        for (Entity entity : list)
        {
            ((RefEntity) entity).setReadOnly();
        }
        cache.getSuperCategory().setReadOnly();
        for (User user : cache.getUsers())
        {
            String id = user.getId();
            String password = entityStore.getPassword(id);
            cache.putPassword(id, password);
        }
    }

    private RaplaDefaultXMLContext createInputContext(EntityStore store, IdCreator idCreator) throws RaplaException
    {
        RaplaDefaultXMLContext inputContext = new IOContext().createInputContext(logger, raplaLocale, i18n, store, idCreator);
        RaplaNonValidatedInput xmlAdapter = new ConfigTools.RaplaReaderImpl();
        inputContext.put(RaplaNonValidatedInput.class, xmlAdapter);
        inputContext.put(Date.class, new Date(getLastUpdated().getTime() - HISTORY_DURATION));
        inputContext.put(EntityHistory.class, history);
        final RaplaDefaultXMLContext inputContext1 = inputContext;
        return inputContext1;
    }

    private RaplaDefaultXMLContext createOutputContext(LocalCache cache) throws RaplaException
    {
        RaplaDefaultXMLContext outputContext = new IOContext().createOutputContext(logger, raplaLocale, i18n, cache.getSuperCategoryProvider(), true);
        outputContext.put(LocalCache.class, cache);
        return outputContext;

    }

    //implement backup at disconnect
    //    final public void backupData() throws RaplaException {
    //        try {
    //
    //            if (backupFileName.length()==0)
    //            	return;
    //
    //            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    //            writeData(buffer);
    //            byte[] data = buffer.toByteArray();
    //            buffer.close();
    //            OutputStream out = new FileOutputStream(backupFileName);
    //            out.write(data);
    //            out.close();
    //            getLogger().info("Backup data to: " + backupFileName);
    //        } catch (IOException e) {
    //            getLogger().error("Backup error: " + e.getMessage());
    //            throw new RaplaException(e.getMessage());
    //        }
    //    }
    //
    //
    //    private void writeData( OutputStream out ) throws IOException, RaplaException
    //    {
    //    	RaplaXMLContext outputContext = new IOContext().createOutputContext( raplaLocale,i18n,cache.getSuperCategoryProvider(), true );
    //        RaplaMainWriter writer = new RaplaMainWriter( outputContext, cache );
    //        writer.setEncoding(backupEncoding);
    //        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(out,backupEncoding));
    //        writer.setWriter(w);
    //        writer.printContent();
    //    }

}
