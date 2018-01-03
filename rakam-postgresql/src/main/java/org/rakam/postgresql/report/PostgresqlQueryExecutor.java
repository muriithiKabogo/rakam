package org.rakam.postgresql.report;

import com.facebook.presto.sql.RakamSqlFormatter;
import com.facebook.presto.sql.parser.ParsingOptions;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.tree.QualifiedName;
import com.facebook.presto.sql.tree.Statement;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.name.Named;
import io.airlift.log.Logger;
import org.rakam.analysis.JDBCPoolDataSource;
import org.rakam.analysis.datasource.CustomDataSource;
import org.rakam.analysis.datasource.CustomDataSourceService;
import org.rakam.analysis.datasource.SupportedCustomDatabase;
import org.rakam.analysis.metadata.Metastore;
import org.rakam.collection.SchemaField;
import org.rakam.config.ProjectConfig;
import org.rakam.report.QueryExecution;
import org.rakam.report.QueryExecutor;
import org.rakam.report.QuerySampling;
import org.rakam.util.JsonHelper;
import org.rakam.util.RakamException;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.EXPECTATION_FAILED;
import static java.lang.String.format;
import static java.util.Optional.ofNullable;
import static org.rakam.util.ValidationUtil.*;

// forbid crosstab, dblink
public class PostgresqlQueryExecutor
        implements QueryExecutor
{
    private final static Logger LOGGER = Logger.get(PostgresqlQueryExecutor.class);
    public final static String MATERIALIZED_VIEW_PREFIX = "$materialized_";
    public final static String CONTINUOUS_QUERY_PREFIX = "$view_";

    private final JDBCPoolDataSource connectionPool;
    protected static final ExecutorService QUERY_EXECUTOR = new ThreadPoolExecutor(0, 1000,
            60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(), new ThreadFactoryBuilder()
            .setNameFormat("jdbc-query-executor").build());
    private final Metastore metastore;
    private final boolean userServiceIsPostgresql;
    private final CustomDataSourceService customDataSource;
    private final ProjectConfig projectConfig;
    private SqlParser sqlParser = new SqlParser();

    @Inject
    public PostgresqlQueryExecutor(
            ProjectConfig projectConfig,
            @Named("store.adapter.postgresql") JDBCPoolDataSource connectionPool,
            Metastore metastore,
            @Nullable CustomDataSourceService customDataSource,
            @Named("user.storage.postgresql") boolean userServiceIsPostgresql)
    {
        this.projectConfig = projectConfig;
        this.connectionPool = connectionPool;
        this.customDataSource = customDataSource;
        this.metastore = metastore;
        this.userServiceIsPostgresql = userServiceIsPostgresql;

        try (Connection connection = connectionPool.getConnection()) {
            connection.createStatement().execute("CREATE OR REPLACE FUNCTION to_unixtime(timestamp) RETURNS double precision" +
                    "    AS 'select extract(epoch from $1);'" +
                    "    LANGUAGE SQL" +
                    "    IMMUTABLE" +
                    "    RETURNS NULL ON NULL INPUT");
        }
        catch (SQLException e) {
            LOGGER.error(e, "Error while creating required Postgresql procedures.");
        }
    }

    @Override
    public QueryExecution executeRawQuery(String query, ZoneId zoneId, Map<String, String> sessionParameters, String apiKey)
    {
        String remotedb = sessionParameters.get("remotedb");
        if (remotedb != null) {
            return getSingleQueryExecution(query, JsonHelper.read(remotedb, new TypeReference<List<CustomDataSource>>() {}));
        }
        return new JDBCQueryExecution(connectionPool::getConnection, query, false, Optional.ofNullable(zoneId), true);
    }

    @Override
    public QueryExecution executeRawQuery(String query, Map<String, String> sessionParameters)
    {
        return executeRawQuery(query, null, sessionParameters, null);
    }

    @Override
    public QueryExecution executeRawStatement(String query)
    {
        return new JDBCQueryExecution(connectionPool::getConnection, query, true, Optional.empty(), true);
    }

    @Override
    public String formatTableReference(String project, QualifiedName name, Optional<QuerySampling> sample, Map<String, String> sessionParameters)
    {
        if (name.getPrefix().isPresent()) {
            String prefix = name.getPrefix().get().toString();
            switch (prefix) {
                case "collection":
                    return checkProject(project, '"') + "." + checkCollection(name.getSuffix()) +
                            sample.map(e -> " TABLESAMPLE " + e.method.name() + "(" + e.percentage + ")").orElse("");
                case "continuous":
                    return checkProject(project, '"') + "." + checkCollection(CONTINUOUS_QUERY_PREFIX + name.getSuffix());
                case "materialized":
                    return checkProject(project, '"') + "." + checkCollection(MATERIALIZED_VIEW_PREFIX + name.getSuffix());
                default:
                    if (customDataSource == null) {
                        throw new RakamException("Schema does not exist: " + name.getPrefix().get().toString(), BAD_REQUEST);
                    }

                    if (prefix.equals("remotefile")) {
                        throw new RakamException("remotefile schema doesn't exist in Postgresql deployment", BAD_REQUEST);
                    }

                    CustomDataSource dataSource = customDataSource.getDatabase(project, prefix);

                    String remoteDb = sessionParameters.get("remotedb");
                    List<CustomDataSource> state;
                    if (remoteDb != null) {
                        state = JsonHelper.read(remoteDb, new TypeReference<List<CustomDataSource>>() {});
                        if (!inSameDatabase(state.get(0), dataSource)) {
                            throw new RakamException("Cross database queries are not supported in Postgresql deployment type.", BAD_REQUEST);
                        }
                        if (!state.stream().anyMatch(e -> e.schemaName.equals(dataSource.schemaName))) {
                            state.add(dataSource);
                        }
                    }
                    else {
                        state = ImmutableList.of(dataSource);
                    }

                    sessionParameters.put("remotedb", JsonHelper.encode(state));

                    return checkProject(prefix, '"') + "." + checkCollection(name.getSuffix());
            }
        }
        else if (name.getSuffix().equals("users")) {
            if (userServiceIsPostgresql) {
                return checkProject(project, '"') + "._users";
            }
            throw new RakamException("User implementation is not supported", EXPECTATION_FAILED);
        }

        if (name.getSuffix().equals("_all") && !name.getPrefix().isPresent()) {
            List<Map.Entry<String, List<SchemaField>>> collections = metastore.getCollections(project).entrySet().stream()
                    .collect(Collectors.toList());
            if (!collections.isEmpty()) {
                String sharedColumns = collections.get(0).getValue().stream()
                        .filter(col -> collections.stream().allMatch(list -> list.getValue().contains(col)))
                        .map(f -> checkTableColumn(f.getName()))
                        .collect(Collectors.joining(", "));

                return "(" + collections.stream().map(Map.Entry::getKey)
                        .map(collection -> format("select cast('%s' as text) as \"_collection\", \"$server_time\" %s from %s t",
                                checkLiteral(collection),
                                sharedColumns.isEmpty() ? "" : (", " + sharedColumns),
                                checkProject(project, '"') + "." + checkCollection(collection)))
                        .collect(Collectors.joining(" union all \n")) + ") _all";
            }
            else {
                return String.format("(select cast(null as text) as \"_collection\", now() as \"$server_time\", cast(null as text) as _user, cast(now() as timestamp) as %s limit 0) _all",
                        checkTableColumn(projectConfig.getTimeColumn()));
            }
        }
        else {
            return checkProject(project, '"') + "." + checkCollection(name.getSuffix());
        }
    }

    private boolean inSameDatabase(CustomDataSource current, CustomDataSource dataSource)
    {
        if (current.schemaName.equals(dataSource)) {
            return true;
        }

        return current.type.equals(dataSource.type)
                && current.options.getHost().equals(dataSource.options.getHost())
                && Objects.equals(current.options.getUsername(), dataSource.options.getUsername())
                && Objects.equals(current.options.getPassword(), dataSource.options.getPassword())
                && Objects.equals(current.options.getPort(), dataSource.options.getPort())
                && Objects.equals(current.options.getEnableSSL(), dataSource.options.getEnableSSL());
    }

    public Connection getConnection()
            throws SQLException
    {
        return connectionPool.getConnection();
    }

    public static char dbSeparator(String externalType)
    {
        switch (externalType) {
            case "POSTGRESQL":
                return '"';
            case "MYSQL":
                return '`';
            default:
                return '"';
        }
    }

    private QueryExecution getSingleQueryExecution(String query, List<CustomDataSource> type)
    {
        char seperator = dbSeparator(type.get(0).type);

        StringBuilder builder = new StringBuilder();
        Statement statement = sqlParser.createStatement(query, new ParsingOptions());

        try {
            new RakamSqlFormatter.Formatter(builder, qualifiedName -> {
                String schema = qualifiedName.getPrefix().get().toString();
                CustomDataSource customDataSource1 = type.stream()
                        .filter(e -> e.schemaName.equals(schema)).findAny()
                        .orElseThrow(() -> new RakamException("Cross database operations are not supported.", BAD_REQUEST));

                return ofNullable(customDataSource1.options.getSchema())
                        .map(e -> e + "." + qualifiedName.getSuffix())
                        .orElse(qualifiedName.getSuffix());
            }, seperator){}.process(statement, 1);
        }
        catch (UnsupportedOperationException e) {
            return null;
        }

        String sqlQuery = builder.toString();

        return new JDBCQueryExecution(() ->
                SupportedCustomDatabase.getAdapter(type.get(0).type).getDataSource().openConnection(type.get(0).options), sqlQuery, false, Optional.empty(), false);
    }
}
