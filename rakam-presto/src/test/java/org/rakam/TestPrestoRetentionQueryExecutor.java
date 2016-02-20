package org.rakam;

import com.facebook.presto.hadoop.shaded.com.google.common.base.Throwables;
import com.google.common.eventbus.EventBus;
import org.rakam.config.JDBCConfig;
import org.rakam.presto.analysis.JDBCMetastore;
import org.rakam.analysis.JDBCPoolDataSource;
import org.rakam.analysis.RetentionQueryExecutor;
import org.rakam.analysis.TestRetentionQueryExecutor;
import org.rakam.collection.FieldDependencyBuilder;
import org.rakam.analysis.metadata.Metastore;
import org.rakam.event.TestingEnvironment;
import org.rakam.plugin.EventStore;
import org.rakam.presto.analysis.PrestoConfig;
import org.rakam.presto.analysis.PrestoQueryExecutor;
import org.rakam.presto.analysis.PrestoRetentionQueryExecutor;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeSuite;

public class TestPrestoRetentionQueryExecutor extends TestRetentionQueryExecutor {

    private TestingEnvironment testingEnvironment;
    private JDBCMetastore metastore;
    private PrestoRetentionQueryExecutor retentionQueryExecutor;
    private TestingPrestoEventStore testingPrestoEventStore;

    @BeforeSuite
    public void setup() throws Exception {
        testingEnvironment = new TestingEnvironment();
        PrestoConfig prestoConfig = testingEnvironment.getPrestoConfig();
        JDBCConfig postgresqlConfig = testingEnvironment.getPostgresqlConfig();

        JDBCPoolDataSource metastoreDataSource = JDBCPoolDataSource.getOrCreateDataSource(postgresqlConfig);
        metastore = new JDBCMetastore(metastoreDataSource, prestoConfig, new EventBus(), new FieldDependencyBuilder().build());
        metastore.setup();

        PrestoQueryExecutor prestoQueryExecutor = new PrestoQueryExecutor(prestoConfig, metastore);

        retentionQueryExecutor = new PrestoRetentionQueryExecutor(prestoQueryExecutor, metastore);
        testingPrestoEventStore = new TestingPrestoEventStore(prestoQueryExecutor, prestoConfig);

        // TODO: Presto throws "No node available" error, find a way to avoid this ugly hack.
        Thread.sleep(1000);
        super.setup();
    }

    @AfterSuite
    public void clean() {
        super.clean();
        try {
            testingEnvironment.close();
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public EventStore getEventStore() {
        return testingPrestoEventStore;
    }

    @Override
    public Metastore getMetastore() {
        return metastore;
    }

    @Override
    public RetentionQueryExecutor getRetentionQueryExecutor() {
        return retentionQueryExecutor;
    }
}