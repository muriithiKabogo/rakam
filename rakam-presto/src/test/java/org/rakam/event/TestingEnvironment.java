package org.rakam.event;

import com.facebook.presto.rakam.RakamPlugin;
import com.facebook.presto.rakam.RakamRaptorPlugin;
import com.facebook.presto.raptor.RaptorPlugin;
import com.facebook.presto.server.testing.TestingPrestoServer;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import com.google.inject.Binder;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import io.airlift.log.Logger;
import io.airlift.testing.postgresql.TestingPostgreSqlServer;
import org.rakam.analysis.JDBCPoolDataSource;
import org.rakam.config.JDBCConfig;
import org.rakam.presto.analysis.PrestoConfig;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.IDBI;

import java.io.IOException;
import java.net.URI;

import static com.google.common.base.Throwables.propagate;
import static java.lang.String.format;

public class TestingEnvironment {
    private final static Logger LOGGER = Logger.get(TestingEnvironment.class);

    private static PrestoConfig prestoConfig;
    private static TestingPrestoServer testingPrestoServer;
    private static TestingPostgreSqlServer testingPostgresqlServer;
    private static JDBCConfig postgresqlConfig;
    private static JDBCPoolDataSource metastore;

    public TestingEnvironment() {
        this(true);
    }

    public TestingEnvironment(boolean installMetadata) {
        try {
            if (testingPrestoServer == null) {
                synchronized (TestingEnvironment.class) {
                    testingPrestoServer = new TestingPrestoServer();

                    String metadataDatabase = Files.createTempDir().getAbsolutePath();
                    RaptorPlugin plugin = new RakamRaptorPlugin();

                    Module metastoreModule = new Module() {
                        @Override
                        public void configure(Binder binder) {
                        }

                        @Provides
                        @Singleton
                        public IDBI getDataSource() {
                            return new DBI(format("jdbc:h2:mem:test%s;DB_CLOSE_DELAY=-1;mode=MySQL", System.nanoTime()));
                        }
                    };

                    testingPrestoServer.installPlugin(plugin);
                    testingPrestoServer.installPlugin(new RakamPlugin());

                    testingPrestoServer.createCatalog("rakam_raptor", "rakam_raptor", ImmutableMap.<String, String>builder()
                            .put("storage.data-directory", Files.createTempDir().getAbsolutePath())
                            .put("metadata.db.type", "h2")
                            .put("metadata.db.connections.wait", "10s")
                            .put("metadata.db.connections.max", "500")
                            .put("metadata.db.mvcc.enabled", "true")
                            .put("metadata.db.filename", metadataDatabase).build());

                    String prestoUrl = "http://" + testingPrestoServer.getAddress().toString();
                    prestoConfig = new PrestoConfig()
                            .setAddress(URI.create(prestoUrl))
                            .setStreamingConnector("streaming")
                            .setColdStorageConnector("rakam_raptor");
                    LOGGER.info("Presto started on " + prestoUrl);

                    metastore = JDBCPoolDataSource.getOrCreateDataSource(new JDBCConfig().setUrl("jdbc:h2:" + metadataDatabase)
                            .setUsername("sa").setPassword(""));

                    System.out.println(prestoConfig.getAddress());
                }
            }

            if (installMetadata) {
                if (testingPostgresqlServer == null) {
                    synchronized (TestingEnvironment.class) {
                        testingPostgresqlServer = new TestingPostgreSqlServer("testuser", "testdb");

                        Runtime.getRuntime().addShutdownHook(
                                new Thread(
                                        () -> {
                                            try {
                                                testingPostgresqlServer.close();
                                            } catch (IOException e) {
                                                e.printStackTrace();
                                            }
                                        }
                                )
                        );
                    }
                }

                postgresqlConfig = new JDBCConfig()
                        .setUrl(testingPostgresqlServer.getJdbcUrl())
                        .setUsername(testingPostgresqlServer.getUser());
                System.out.println("postgresql config: " + postgresqlConfig);
            }
        } catch (Exception e) {
            throw propagate(e);
        }
    }

    public JDBCPoolDataSource getPrestoMetastore() {
        return metastore;
    }

    public JDBCConfig getPostgresqlConfig() {
        if (postgresqlConfig == null) {
            throw new UnsupportedOperationException();
        }
        return postgresqlConfig;
    }

    public PrestoConfig getPrestoConfig() {
        return prestoConfig;
    }

    public void close()
            throws Exception {
        testingPrestoServer.close();
        if (testingPostgresqlServer != null) {
            testingPostgresqlServer.close();
        }
    }
}
