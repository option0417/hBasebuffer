package tw.com.wd.hbase.util.hbasebuffer.impl;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.filter.FirstKeyOnlyFilter;
import org.junit.*;
import tw.com.wd.hbase.util.hbasebuffer.IHBaseBuffer;
import tw.com.wd.hbase.util.hbasebuffer.util.ObjectUtils;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.*;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;

/**
 *
 * 1000 Worker do 100 = 100000 put and done in 1998 millitime
 * 1000 Worker do 500 = 500000 put and done in 5393 millitime
 * 1000 Worker do 1000 = 1000000 put and done in 9320 millitime
 * 1500 Worker do 1000 = 1500000 put and done in 12467 millitime
 */
public class HBaseBufferTest {
    private static final int THREAD_CORE_SIZE                   = Runtime.getRuntime().availableProcessors() << 1;
    private static final int TASK_QUEUE_SIZE                    = (THREAD_CORE_SIZE << 3) + (THREAD_CORE_SIZE << 1);
    private static ExecutorService hConnPool                    = null;
    private static final String HBASE_ENV_KEY_ZOOKEEPER_QUORUM  = "hbase.zookeeper.quorum";
    private static final String HBASE_ENV_ROOT_DIR              = "hbase.root.dir";
    private static final int WORKER_SIZE                        = 100;
    private static final int PUT_COUNT                          = 500;
    private static final TableName TBL1                         = TableName.valueOf("testBuffer1");
    private static final TableName TBL2                         = TableName.valueOf("testBuffer2");
    private static final TableName TBL3                         = TableName.valueOf("testBuffer3");

    private static Configuration conf;
    private static Connection hConn;

    private IHBaseBuffer hbaseBuffer;
    private ExecutorService workerPool;
    private List<Future<Boolean>> futureList;

    @BeforeClass
    public static void setupClass() throws IOException {
        hConnPool =
                new ThreadPoolExecutor(
                        THREAD_CORE_SIZE,
                        THREAD_CORE_SIZE + (THREAD_CORE_SIZE >> 1),
                        1l,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<Runnable>(TASK_QUEUE_SIZE));

        conf = new Configuration();
        conf.set(HBASE_ENV_KEY_ZOOKEEPER_QUORUM, "nqmi11");
        conf.set(HBASE_ENV_ROOT_DIR, "hdfs://nqmi11:8020/hbasebuffer");
        hConn = ConnectionFactory.createConnection(conf, hConnPool);

        Admin admin = hConn.getAdmin();

        if (!admin.tableExists(TBL1)) {
            HTableDescriptor hTableDescriptor = new HTableDescriptor(TBL1);
            HColumnDescriptor hColumnDescriptor = new HColumnDescriptor("cf");
            hTableDescriptor.addFamily(hColumnDescriptor);
            admin.createTable(hTableDescriptor);
        }

        if (!admin.tableExists(TBL2)) {
            HTableDescriptor hTableDescriptor = new HTableDescriptor(TBL2);
            HColumnDescriptor hColumnDescriptor = new HColumnDescriptor("cf");
            hTableDescriptor.addFamily(hColumnDescriptor);
            admin.createTable(hTableDescriptor);
        }

        if (!admin.tableExists(TBL3)) {
            HTableDescriptor hTableDescriptor = new HTableDescriptor(TBL3);
            HColumnDescriptor hColumnDescriptor = new HColumnDescriptor("cf");
            hTableDescriptor.addFamily(hColumnDescriptor);
            admin.createTable(hTableDescriptor);
        }
        admin.close();
    }

    @AfterClass
    public static void tearDownClass() throws IOException {
        Admin admin = hConn.getAdmin();

        if (admin.tableExists(TBL1)) {
            admin.disableTable(TBL1);
            admin.deleteTable(TBL1);
        }

        if (admin.tableExists(TBL2)) {
            admin.disableTable(TBL2);
            admin.deleteTable(TBL2);
        }

        if (admin.tableExists(TBL3)) {
            admin.disableTable(TBL3);
            admin.deleteTable(TBL3);
        }

        admin.close();
        hConn.close();
    }

    @Before
    public void preTest() throws IOException {
        hbaseBuffer = HBaseBuffer.getInstance();
        ObjectUtils.injectObject(hbaseBuffer, hConn);

        workerPool = Executors.newFixedThreadPool(WORKER_SIZE);
        futureList = new ArrayList<Future<Boolean>>(WORKER_SIZE);
    }

    @After
    public void postTest() throws IOException {
        Admin admin = hConn.getAdmin();

        if (admin.tableExists(TBL1)) {
            admin.disableTable(TBL1);
            admin.truncateTable(TBL1, true);
        }

        if (admin.tableExists(TBL2)) {
            admin.disableTable(TBL2);
            admin.truncateTable(TBL2, true);
        }

        if (admin.tableExists(TBL3)) {
            admin.disableTable(TBL3);
            admin.truncateTable(TBL3, true);
        }
        admin.close();
    }

    @Test
    public void testPut() throws Exception {
        Exception rtnException  = null;
        long startime           = System.currentTimeMillis();

        try {
            for (int cnt = 0; cnt < WORKER_SIZE; cnt++) {
                futureList.add(workerPool.submit(new PutWorker(cnt, TBL1, hbaseBuffer, PUT_COUNT)));
            }

            int  cnt = 0;
            while (cnt != futureList.size()) {
                cnt = 0;
                for (int idx = 0; idx < futureList.size(); idx++) {
                    if (futureList.get(idx).isDone()) {
                        cnt++;
                    }
                }
            }
            long endtime = System.currentTimeMillis();
            System.out.printf("%d Worker do %d put and done in %d millisecond\n", WORKER_SIZE, PUT_COUNT, endtime - startime);
        } catch (Exception e) {
            rtnException = e;
            e.printStackTrace();
        }

        assertThat(rtnException, is(nullValue()));
        for (Future<Boolean> f : futureList) {
            assertThat(f.get(), is(Boolean.TRUE));
        }
        Thread.sleep(1000l);
        assertThat(checkRecordCount(TBL1), is(WORKER_SIZE * PUT_COUNT));
    }

    @Test
    public void testPutMultiTable() throws Exception {
        Exception rtnException  = null;
        long startime           = System.currentTimeMillis();
        int  tbl1Cnt            = 0;
        int  tbl2Cnt            = 0;
        int  tbl3Cnt            = 0;

        try {
            for (int cnt = 0; cnt < WORKER_SIZE; cnt++) {
                if (cnt % 3 == 0) {
                    futureList.add(workerPool.submit(new PutWorker(cnt, TBL1, hbaseBuffer, PUT_COUNT)));
                    tbl1Cnt++;
                } else if (cnt % 3 == 1) {
                    futureList.add(workerPool.submit(new PutWorker(cnt, TBL2, hbaseBuffer, PUT_COUNT)));
                    tbl2Cnt++;
                } else {
                    futureList.add(workerPool.submit(new PutWorker(cnt, TBL3, hbaseBuffer, PUT_COUNT)));
                    tbl3Cnt++;
                }
            }

            int  cnt = 0;
            while (cnt != futureList.size()) {
                cnt = 0;
                for (int idx = 0; idx < futureList.size(); idx++) {
                    if (futureList.get(idx).isDone()) {
                        cnt++;
                    }
                }
            }
            long endtime = System.currentTimeMillis();
            System.out.printf("%d Worker do %d put and done in %d millitime\n", WORKER_SIZE, PUT_COUNT, endtime - startime);
        } catch (Exception e) {
            rtnException = e;
            e.printStackTrace();
        }



        assertThat(rtnException, is(nullValue()));
        for (Future<Boolean> f : futureList) {
            assertThat(f.get(), is(Boolean.TRUE));
        }
        Thread.sleep(1000l);
        assertThat(checkRecordCount(TBL1), is(tbl1Cnt * PUT_COUNT));
        assertThat(checkRecordCount(TBL2), is(tbl2Cnt * PUT_COUNT));
        assertThat(checkRecordCount(TBL3), is(tbl3Cnt * PUT_COUNT));
    }

    @Test
    public void testWithReflection() throws Exception {
        Exception rtnException  = null;
        try {
            Class<?> hbufferClazz   = Class.forName("tw.com.wd.hbase.util.hbasebuffer.impl.HBaseBuffer");
            Method getInstanceMethod    = hbufferClazz.getDeclaredMethod("getInstance");

            Object hbufferInstance = getInstanceMethod.invoke(null);

            Method putMethod = hbufferClazz.getDeclaredMethod("put", Row.class, TableName.class);


            Put put = new Put("put_with_reflection".getBytes());
            put.addColumn("cf".getBytes(), "cq".getBytes(), "put_with_reflection".getBytes());
            putMethod.invoke(hbufferInstance, put, TBL1);
        } catch (Exception e) {
            rtnException = e;
            e.printStackTrace();
        }
        Thread.sleep(1000l);
        assertThat(rtnException, is(nullValue()));
        assertThat(checkRecordCount(TBL1), is(1));
    }

    private int checkRecordCount(TableName tbl) throws IOException {
        int currCount   = 0;
        Table htbl      = null;

        try {
            htbl        = hConn.getTable(tbl);
            Scan scan   = new Scan();

            scan.setFilter(
                    new FirstKeyOnlyFilter()
            );

            ResultScanner resultScanner = htbl.getScanner(scan);
            Iterator<Result> iter       = resultScanner.iterator();
            while (iter.hasNext()) {
                iter.next();
                currCount++;
            }
            return currCount;
        } finally {
            if (htbl != null) {
                htbl.close();
            }
        }
    }

    private class PutWorker implements Callable<Boolean> {
        private int id;
        private int putCount;
        private TableName tbl;
        private IHBaseBuffer hbaseBuffer;

        public PutWorker(int id, TableName tbl, IHBaseBuffer hbaseBuffer, int putCount) {
            super();
            this.id             = id;
            this.tbl            = tbl;
            this.putCount       = putCount;
            this.hbaseBuffer    = hbaseBuffer;
        }

        public Boolean call() throws Exception {
            boolean flag = true;
            for (int cnt = 0; cnt < putCount; cnt++) {
                Put put = new Put((id + "_" + cnt).getBytes());
                put.addColumn("cf".getBytes(), "cq".getBytes(), (id + "_" + cnt).getBytes());

                try {
                    if (!this.hbaseBuffer.put(put, tbl)) {
                        flag = false;
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
            return flag;
        }
    }
}
