/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hive.streaming;


import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.common.HeapMemoryMonitor;
import org.apache.hadoop.hive.common.JavaUtils;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.llap.LlapUtil;
import org.apache.hadoop.hive.metastore.Warehouse;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.hive_metastoreConstants;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.io.AcidOutputFormat;
import org.apache.hadoop.hive.ql.io.RecordUpdater;
import org.apache.hadoop.hive.ql.metadata.Table;
import org.apache.hadoop.hive.serde2.AbstractSerDe;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorUtils;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.SubStructObjectInspector;
import org.apache.hadoop.util.ReflectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractRecordWriter implements RecordWriter {
  private static final Logger LOG = LoggerFactory.getLogger(AbstractRecordWriter.class.getName());

  protected HiveConf conf;
  private StreamingConnection conn;
  protected Table table;
  List<String> inputColumns;
  List<String> inputTypes;
  private String fullyQualifiedTableName;
  private Map<String, List<RecordUpdater>> updaters = new HashMap<>();
  private Map<String, Path> partitionPaths = new HashMap<>();
  private Set<String> addedPartitions = new HashSet<>();
  // input OI includes table columns + partition columns
  private StructObjectInspector inputRowObjectInspector;
  // output OI strips off the partition columns and retains other columns
  private ObjectInspector outputRowObjectInspector;
  private List<String> partitionColumns = new ArrayList<>();
  private ObjectInspector[] partitionObjInspectors = null;
  private StructField[] partitionStructFields = null;
  private Object[] partitionFieldData;
  private ObjectInspector[] bucketObjInspectors = null;
  private StructField[] bucketStructFields = null;
  private Object[] bucketFieldData;
  private List<Integer> bucketIds = new ArrayList<>();
  private int totalBuckets;
  private String defaultPartitionName;
  private boolean isBucketed;
  private AcidOutputFormat<?, ?> acidOutputFormat;
  private Long curBatchMinWriteId;
  private Long curBatchMaxWriteId;
  private HeapMemoryMonitor heapMemoryMonitor;
  // if low memory canary is set and if records after set canary exceeds threshold, trigger a flush.
  // This is to avoid getting notified of low memory too often and flushing too often.
  private AtomicBoolean lowMemoryCanary;
  private long ingestSizeBytes = 0;
  private boolean autoFlush;
  private float memoryUsageThreshold;
  private long ingestSizeThreshold;

  private static class OrcMemoryPressureMonitor implements HeapMemoryMonitor.Listener {
    private static final Logger LOG = LoggerFactory.getLogger(OrcMemoryPressureMonitor.class.getName());
    private final AtomicBoolean lowMemoryCanary;

    OrcMemoryPressureMonitor(final AtomicBoolean lowMemoryCanary) {
      this.lowMemoryCanary = lowMemoryCanary;
    }

    @Override
    public void memoryUsageAboveThreshold(final long usedMemory, final long maxMemory) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Orc memory pressure notified! usedMemory: {} maxMemory: {}.",
          LlapUtil.humanReadableByteCount(usedMemory), LlapUtil.humanReadableByteCount(maxMemory));
      }
      lowMemoryCanary.set(true);
    }
  }

  @Override
  public void init(StreamingConnection conn, long minWriteId, long maxWriteId) throws StreamingException {
    if (conn == null) {
      throw new StreamingException("Streaming connection cannot be null during record writer initialization");
    }
    try {
      this.conn = conn;
      this.curBatchMinWriteId = minWriteId;
      this.curBatchMaxWriteId = maxWriteId;
      this.conf = conn.getHiveConf();
      this.defaultPartitionName = conf.getVar(HiveConf.ConfVars.DEFAULTPARTITIONNAME);
      this.table = conn.getTable();
      this.inputColumns = table.getSd().getCols().stream().map(FieldSchema::getName).collect(Collectors.toList());
      this.inputTypes = table.getSd().getCols().stream().map(FieldSchema::getType).collect(Collectors.toList());
      if (conn.isPartitionedTable() && conn.isDynamicPartitioning()) {
        this.partitionColumns = table.getPartitionKeys().stream().map(FieldSchema::getName)
          .collect(Collectors.toList());
        this.inputColumns.addAll(partitionColumns);
        this.inputTypes
          .addAll(table.getPartitionKeys().stream().map(FieldSchema::getType).collect(Collectors.toList()));
      }
      this.fullyQualifiedTableName = Warehouse.getQualifiedName(table.getDbName(), table.getTableName());
      String outFormatName = this.table.getSd().getOutputFormat();
      this.acidOutputFormat = (AcidOutputFormat<?, ?>) ReflectionUtils
        .newInstance(JavaUtils.loadClass(outFormatName), conf);
      setupMemoryMonitoring();
    } catch (ClassNotFoundException e) {
      throw new StreamingException(e.getMessage(), e);
    }

    try {
      final AbstractSerDe serDe = createSerde();
      this.inputRowObjectInspector = (StructObjectInspector) serDe.getObjectInspector();
      if (conn.isPartitionedTable() && conn.isDynamicPartitioning()) {
        preparePartitioningFields();
        int dpStartCol = inputRowObjectInspector.getAllStructFieldRefs().size() - table.getPartitionKeys().size();
        this.outputRowObjectInspector = new SubStructObjectInspector(inputRowObjectInspector, 0, dpStartCol);
      } else {
        this.outputRowObjectInspector = inputRowObjectInspector;
      }
      prepareBucketingFields();
    } catch (SerDeException e) {
      throw new StreamingException("Unable to create SerDe", e);
    }
  }

  private void setupMemoryMonitoring() {
    this.autoFlush = conf.getBoolVar(HiveConf.ConfVars.HIVE_STREAMING_AUTO_FLUSH_ENABLED);
    this.memoryUsageThreshold = conf.getFloatVar(HiveConf.ConfVars.HIVE_HEAP_MEMORY_MONITOR_USAGE_THRESHOLD);
    this.ingestSizeThreshold = conf.getSizeVar(HiveConf.ConfVars.HIVE_STREAMING_AUTO_FLUSH_CHECK_INTERVAL_SIZE);
    LOG.info("Memory monitorings settings - autoFlush: {} memoryUsageThreshold: {} ingestSizeThreshold: {}",
      autoFlush, memoryUsageThreshold, ingestSizeBytes);
    this.heapMemoryMonitor = new HeapMemoryMonitor(memoryUsageThreshold);
    MemoryUsage tenuredMemUsage = heapMemoryMonitor.getTenuredGenMemoryUsage();
    if (tenuredMemUsage != null) {
      lowMemoryCanary = new AtomicBoolean(false);
      heapMemoryMonitor.registerListener(new OrcMemoryPressureMonitor(lowMemoryCanary));
      heapMemoryMonitor.start();
      // alert if we already running low on memory (starting with low memory will lead to frequent auto flush)
      float currentUsage = (float) tenuredMemUsage.getUsed() / (float) tenuredMemUsage.getMax();
      if (currentUsage > memoryUsageThreshold) {
        LOG.warn("LOW MEMORY ALERT! Tenured gen memory is already low. Increase memory to improve performance." +
            " Used: {} Max: {}", LlapUtil.humanReadableByteCount(tenuredMemUsage.getUsed()),
          LlapUtil.humanReadableByteCount(tenuredMemUsage.getMax()));
      }
    }
  }

  private void prepareBucketingFields() {
    this.isBucketed = table.getSd().getNumBuckets() > 0;
    // For unbucketed tables we have exactly 1 RecordUpdater (until HIVE-19208) for each AbstractRecordWriter which
    // ends up writing to a file bucket_000000.
    // See also {@link #getBucket(Object)}
    this.totalBuckets = isBucketed ? table.getSd().getNumBuckets() : 1;
    if (isBucketed) {
      this.bucketIds = getBucketColIDs(table.getSd().getBucketCols(), table.getSd().getCols());
      this.bucketFieldData = new Object[bucketIds.size()];
      this.bucketObjInspectors = getObjectInspectorsForBucketedCols(bucketIds, inputRowObjectInspector);
      this.bucketStructFields = new StructField[bucketIds.size()];
      List<? extends StructField> allFields = inputRowObjectInspector.getAllStructFieldRefs();
      for (int i = 0; i < bucketIds.size(); i++) {
        bucketStructFields[i] = allFields.get(bucketIds.get(i));
      }
    }
  }

  private void preparePartitioningFields() {
    final int numPartitions = table.getPartitionKeys().size();
    this.partitionFieldData = new Object[numPartitions];
    this.partitionObjInspectors = new ObjectInspector[numPartitions];
    int startIdx = inputRowObjectInspector.getAllStructFieldRefs().size() - numPartitions;
    int endIdx = inputRowObjectInspector.getAllStructFieldRefs().size();
    int j = 0;
    for (int i = startIdx; i < endIdx; i++) {
      StructField structField = inputRowObjectInspector.getAllStructFieldRefs().get(i);
      partitionObjInspectors[j++] = structField.getFieldObjectInspector();
    }
    this.partitionStructFields = new StructField[partitionColumns.size()];
    for (int i = 0; i < partitionColumns.size(); i++) {
      String partCol = partitionColumns.get(i);
      partitionStructFields[i] = inputRowObjectInspector.getStructFieldRef(partCol);
    }
  }

  /**
   * used to tag error msgs to provided some breadcrumbs
   */
  private String getWatermark(String partition) {
    return partition + " writeIds[" + curBatchMinWriteId + "," + curBatchMaxWriteId + "]";
  }

  // return the column numbers of the bucketed columns
  private List<Integer> getBucketColIDs(List<String> bucketCols, List<FieldSchema> cols) {
    ArrayList<Integer> result = new ArrayList<>(bucketCols.size());
    HashSet<String> bucketSet = new HashSet<>(bucketCols);
    for (int i = 0; i < cols.size(); i++) {
      if (bucketSet.contains(cols.get(i).getName())) {
        result.add(i);
      }
    }
    return result;
  }

  /**
   * Create SerDe for the record writer.
   *
   * @return - serde
   * @throws SerializationError - if serde cannot be created.
   */
  public abstract AbstractSerDe createSerde() throws SerializationError;

  /**
   * Encode a record as an Object that Hive can read with the ObjectInspector associated with the
   * serde returned by {@link #createSerde}.  This is public so that test frameworks can use it.
   *
   * @param record record to be deserialized
   * @return deserialized record as an Object
   * @throws SerializationError - any error during serialization or deserialization of record
   */
  public abstract Object encode(byte[] record) throws SerializationError;

  // returns the bucket number to which the record belongs to
  private int getBucket(Object row) {
    if (!isBucketed) {
      return 0;
    }
    Object[] bucketFields = getBucketFields(row);
    int bucketingVersion = Utilities.getBucketingVersion(
      table.getParameters().get(hive_metastoreConstants.TABLE_BUCKETING_VERSION));

    return bucketingVersion == 2 ?
      ObjectInspectorUtils.getBucketNumber(bucketFields, bucketObjInspectors, totalBuckets) :
      ObjectInspectorUtils.getBucketNumberOld(bucketFields, bucketObjInspectors, totalBuckets);
  }

  private List<String> getPartitionValues(final Object row) {
    if (!conn.isPartitionedTable()) {
      return null;
    }
    List<String> partitionValues = new ArrayList<>();
    if (conn.isPartitionedTable() && conn.isDynamicPartitioning()) {
      Object[] partitionFields = getPartitionFields(row);
      for (int i = 0; i < partitionObjInspectors.length; i++) {
        ObjectInspector oi = partitionObjInspectors[i];
        Object field = partitionFields[i];
        Object partitionValue = ObjectInspectorUtils.copyToStandardObject(field, oi, ObjectInspectorUtils
          .ObjectInspectorCopyOption.WRITABLE);
        if (partitionValue == null || partitionValue.toString().length() == 0) {
          partitionValues.add(defaultPartitionName);
        } else {
          partitionValues.add(partitionValue.toString());
        }
      }
    } else {
      partitionValues = conn.getStaticPartitionValues();
    }
    return partitionValues;
  }

  @Override
  public void flush() throws StreamingIOFailure {
    try {
      logStats("Stats before flush:");
      for (Map.Entry<String, List<RecordUpdater>> entry : updaters.entrySet()) {
        LOG.info("Flushing record updater for partitions: {}", entry.getKey());
        for (RecordUpdater updater : entry.getValue()) {
          if (updater != null) {
            updater.flush();
          }
        }
      }
      ingestSizeBytes = 0;
      logStats("Stats after flush:");
    } catch (IOException e) {
      throw new StreamingIOFailure("Unable to flush recordUpdater", e);
    }
  }

  @Override
  public void close() throws StreamingIOFailure {
    boolean haveError = false;
    String partition = null;
    logStats("Stats before close:");
    for (Map.Entry<String, List<RecordUpdater>> entry : updaters.entrySet()) {
      partition = entry.getKey();
      LOG.info("Closing updater for partitions: {}", partition);
      for (RecordUpdater updater : entry.getValue()) {
        if (updater != null) {
          try {
            //try not to leave any files open
            updater.close(false);
          } catch (Exception ex) {
            haveError = true;
            LOG.error("Unable to close " + updater + " due to: " + ex.getMessage(), ex);
          }
        }
      }
      entry.getValue().clear();
    }
    updaters.clear();
    logStats("Stats after close:");
    if (haveError) {
      throw new StreamingIOFailure("Encountered errors while closing (see logs) " + getWatermark(partition));
    }
  }

  private static ObjectInspector[] getObjectInspectorsForBucketedCols(List<Integer> bucketIds
    , StructObjectInspector recordObjInspector) {
    ObjectInspector[] result = new ObjectInspector[bucketIds.size()];

    for (int i = 0; i < bucketIds.size(); i++) {
      int bucketId = bucketIds.get(i);
      result[i] =
        recordObjInspector.getAllStructFieldRefs().get(bucketId).getFieldObjectInspector();
    }
    return result;
  }

  private Object[] getBucketFields(Object row) {
    for (int i = 0; i < bucketIds.size(); i++) {
      bucketFieldData[i] = inputRowObjectInspector.getStructFieldData(row, bucketStructFields[i]);
    }
    return bucketFieldData;
  }

  private Object[] getPartitionFields(Object row) {
    for (int i = 0; i < partitionFieldData.length; i++) {
      partitionFieldData[i] = inputRowObjectInspector.getStructFieldData(row, partitionStructFields[i]);
    }
    return partitionFieldData;
  }

  @Override
  public void write(final long writeId, final byte[] record) throws StreamingException {
    checkAutoFlush();
    ingestSizeBytes += record.length;
    try {
      Object encodedRow = encode(record);
      int bucket = getBucket(encodedRow);
      List<String> partitionValues = getPartitionValues(encodedRow);
      getRecordUpdater(partitionValues, bucket).insert(writeId, encodedRow);
    } catch (IOException e) {
      throw new StreamingIOFailure("Error writing record in transaction write id ("
        + writeId + ")", e);
    }
  }

  private void checkAutoFlush() throws StreamingIOFailure {
    if (!autoFlush) {
      return;
    }
    if (lowMemoryCanary != null) {
      if (lowMemoryCanary.get() && ingestSizeBytes > ingestSizeThreshold) {
        LOG.info("Low memory canary is set and ingestion size (buffered) threshold '{}' exceeded. " +
          "Flushing all record updaters..", LlapUtil.humanReadableByteCount(ingestSizeThreshold));
        flush();
        conn.getConnectionStats().incrementAutoFlushCount();
        lowMemoryCanary.set(false);
      }
    } else {
      if (ingestSizeBytes > ingestSizeThreshold) {
        MemoryMXBean mxBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = mxBean.getHeapMemoryUsage();
        float memUsedFraction = ((float) heapUsage.getUsed() / (float) heapUsage.getMax());
        if (memUsedFraction > memoryUsageThreshold) {
          LOG.info("Memory usage threshold '{}' and ingestion size (buffered) threshold '{}' exceeded. " +
            "Flushing all record updaters..", memUsedFraction, LlapUtil.humanReadableByteCount(ingestSizeThreshold));
          flush();
          conn.getConnectionStats().incrementAutoFlushCount();
        }
      }
    }
  }

  @Override
  public Set<String> getPartitions() {
    return addedPartitions;
  }

  private RecordUpdater createRecordUpdater(final Path partitionPath, int bucketId, Long minWriteId,
    Long maxWriteID)
    throws IOException {
    // Initialize table properties from the table parameters. This is required because the table
    // may define certain table parameters that may be required while writing. The table parameter
    // 'transactional_properties' is one such example.
    Properties tblProperties = new Properties();
    tblProperties.putAll(table.getParameters());
    return acidOutputFormat.getRecordUpdater(partitionPath,
      new AcidOutputFormat.Options(conf)
        .inspector(outputRowObjectInspector)
        .bucket(bucketId)
        .tableProperties(tblProperties)
        .minimumWriteId(minWriteId)
        .maximumWriteId(maxWriteID)
        .statementId(-1)
        .finalDestination(partitionPath));
  }

  private RecordUpdater getRecordUpdater(List<String> partitionValues, int bucketId) throws StreamingIOFailure {
    RecordUpdater recordUpdater;
    String key;
    Path destLocation;
    try {
      key = partitionValues == null ? fullyQualifiedTableName : partitionValues.toString();
      // add partition in metastore for dynamic partition. We make a metastore call for every new partition value that
      // we encounter even if partition already exists (exists check require a metastore call anyways).
      if (partitionPaths.containsKey(key)) {
        destLocation = partitionPaths.get(key);
      } else {
        // un-partitioned table
        if (partitionValues == null) {
          destLocation = new Path(table.getSd().getLocation());
        } else {
          PartitionInfo partitionInfo = conn.createPartitionIfNotExists(partitionValues);
          // collect the newly added partitions. connection.commitTransaction() will report the dynamically added
          // partitions to TxnHandler
          if (!partitionInfo.isExists()) {
            addedPartitions.add(partitionInfo.getName());
            LOG.info("Created partition {} for table {}", partitionInfo.getName(), fullyQualifiedTableName);
          } else {
            LOG.info("Partition {} already exists for table {}", partitionInfo.getName(), fullyQualifiedTableName);
          }
          destLocation = new Path(partitionInfo.getPartitionLocation());
        }
        partitionPaths.put(key, destLocation);
      }
      updaters.computeIfAbsent(key, k -> initializeBuckets());
      recordUpdater = updaters.get(key).get(bucketId);
    } catch (StreamingException e) {
      throw new StreamingIOFailure("Unable to create partition: " + partitionValues + "for " + conn, e);
    }
    if (recordUpdater == null) {
      try {
        recordUpdater = createRecordUpdater(destLocation, bucketId, curBatchMinWriteId, curBatchMaxWriteId);
      } catch (IOException e) {
        String errMsg = "Failed creating RecordUpdater for " + getWatermark(destLocation.toString());
        LOG.error(errMsg, e);
        throw new StreamingIOFailure(errMsg, e);
      }
      List<RecordUpdater> partitionUpdaters = updaters.get(key);
      partitionUpdaters.set(bucketId, recordUpdater);
    }
    return recordUpdater;
  }

  private List<RecordUpdater> initializeBuckets() {
    List<RecordUpdater> result = new ArrayList<>(totalBuckets);
    for (int bucket = 0; bucket < totalBuckets; bucket++) {
      result.add(bucket, null); //so that get(i) returns null rather than ArrayOutOfBounds
    }
    return result;
  }

  private void logStats(final String prefix) {
    int openRecordUpdaters = updaters.values()
      .stream()
      .mapToInt(List::size)
      .sum();
    long bufferedRecords = updaters.values()
      .stream()
      .flatMap(List::stream)
      .filter(Objects::nonNull)
      .mapToLong(RecordUpdater::getBufferedRowCount)
      .sum();
    MemoryUsage memoryUsage = heapMemoryMonitor.getTenuredGenMemoryUsage();
    String oldGenUsage = "NA";
    if (memoryUsage != null) {
      oldGenUsage = "used/max => " + LlapUtil.humanReadableByteCount(memoryUsage.getUsed()) + "/" +
        LlapUtil.humanReadableByteCount(memoryUsage.getMax());
    }
    LOG.info("{} [record-updaters: {}, partitions: {}, buffered-records: {} total-records: {} " +
        "buffered-ingest-size: {}, total-ingest-size: {} tenured-memory-usage: {}]", prefix, openRecordUpdaters,
      partitionPaths.size(), bufferedRecords, conn.getConnectionStats().getRecordsWritten(),
      LlapUtil.humanReadableByteCount(ingestSizeBytes),
      LlapUtil.humanReadableByteCount(conn.getConnectionStats().getRecordsSize()), oldGenUsage);
  }
}
