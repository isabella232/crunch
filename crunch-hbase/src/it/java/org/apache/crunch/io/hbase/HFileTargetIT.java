/**
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
package org.apache.crunch.io.hbase;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.io.Resources;
import org.apache.commons.io.IOUtils;
import org.apache.crunch.DoFn;
import org.apache.crunch.Emitter;
import org.apache.crunch.FilterFn;
import org.apache.crunch.GroupingOptions;
import org.apache.crunch.MapFn;
import org.apache.crunch.PCollection;
import org.apache.crunch.PTable;
import org.apache.crunch.Pair;
import org.apache.crunch.Pipeline;
import org.apache.crunch.PipelineResult;
import org.apache.crunch.fn.FilterFns;
import org.apache.crunch.impl.dist.DistributedPipeline;
import org.apache.crunch.impl.mr.MRPipeline;
import org.apache.crunch.io.At;
import org.apache.crunch.io.CompositePathIterable;
import org.apache.crunch.io.seq.SeqFileReaderFactory;
import org.apache.crunch.test.TemporaryPath;
import org.apache.crunch.test.TemporaryPaths;
import org.apache.crunch.types.writable.WritableDeepCopier;
import org.apache.crunch.types.writable.Writables;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.KeyValueUtil;
import org.apache.hadoop.hbase.Tag;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.io.encoding.DataBlockEncoding;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.io.hfile.HFile;
import org.apache.hadoop.hbase.mapreduce.LoadIncrementalHFiles;
import org.apache.hadoop.hbase.regionserver.KeyValueHeap;
import org.apache.hadoop.hbase.regionserver.KeyValueScanner;
import org.apache.hadoop.hbase.regionserver.StoreFile;
import org.apache.hadoop.hbase.regionserver.StoreFileScanner;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.SequenceFile;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.apache.crunch.types.writable.Writables.nulls;
import static org.apache.crunch.types.writable.Writables.tableOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HFileTargetIT implements Serializable {

  private static HBaseTestingUtility HBASE_TEST_UTILITY;
  private static final byte[] TEST_FAMILY = Bytes.toBytes("test_family");
  private static final byte[] TEST_QUALIFIER = Bytes.toBytes("count");
  private static final Path TEMP_DIR = new Path("/tmp");
  private static final Random RANDOM = new Random();

  private static final FilterFn<String> SHORT_WORD_FILTER = new FilterFn<String>() {
    @Override
    public boolean accept(String input) {
      return input.length() <= 2;
    }
  };

  @Rule
  public transient TemporaryPath tmpDir = TemporaryPaths.create();

  @BeforeClass
  public static void setUpClass() throws Exception {
    // We have to use mini mapreduce cluster, because LocalJobRunner allows only a single reducer
    // (we will need it to test bulk load against multiple regions).
    Configuration conf = HBaseConfiguration.create();

    // Workaround for HBASE-5711, we need to set config value dfs.datanode.data.dir.perm
    // equal to the permissions of the temp dirs on the filesystem. These temp dirs were
    // probably created using this process' umask. So we guess the temp dir permissions as
    // 0777 & ~umask, and use that to set the config value.
    Process process = Runtime.getRuntime().exec("/bin/sh -c umask");
    BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), Charset.forName("UTF-8")));
    int rc = process.waitFor();
    if(rc == 0) {
      String umask = br.readLine();

      int umaskBits = Integer.parseInt(umask, 8);
      int permBits = 0777 & ~umaskBits;
      String perms = Integer.toString(permBits, 8);

      conf.set("dfs.datanode.data.dir.perm", perms);
    }

    HBASE_TEST_UTILITY = new HBaseTestingUtility(conf);
    HBASE_TEST_UTILITY.startMiniCluster(1);
  }

  private static HTable createTable(int splits) throws Exception {
    HColumnDescriptor hcol = new HColumnDescriptor(TEST_FAMILY);
    return createTable(splits, hcol);
  }

  private static HTable createTable(int splits, HColumnDescriptor... hcols) throws Exception {
    byte[] tableName = Bytes.toBytes("test_table_" + RANDOM.nextInt(1000000000));
    HBaseAdmin admin = HBASE_TEST_UTILITY.getHBaseAdmin();
    HTableDescriptor htable = new HTableDescriptor(tableName);
    for (HColumnDescriptor hcol : hcols) {
      htable.addFamily(hcol);
    }
    admin.createTable(htable, Bytes.split(Bytes.toBytes("a"), Bytes.toBytes("z"), splits));
    HBASE_TEST_UTILITY.waitTableAvailable(tableName, 30000);
    return new HTable(HBASE_TEST_UTILITY.getConfiguration(), tableName);
  }

  @AfterClass
  public static void tearDownClass() throws Exception {
    HBASE_TEST_UTILITY.shutdownMiniCluster();
  }

  @Before
  public void setUp() throws IOException {
    FileSystem fs = HBASE_TEST_UTILITY.getTestFileSystem();
    fs.delete(TEMP_DIR, true);
  }

  @Test
  public void testHFileTarget() throws Exception {
    Pipeline pipeline = new MRPipeline(HFileTargetIT.class, HBASE_TEST_UTILITY.getConfiguration());
    Path inputPath = copyResourceFileToHDFS("shakes.txt");
    Path outputPath = getTempPathOnHDFS("out");

    PCollection<String> shakespeare = pipeline.read(At.textFile(inputPath, Writables.strings()));
    PCollection<String> words = split(shakespeare, "\\s+");
    PTable<String, Long> wordCounts = words.count();
    pipeline.write(convertToKeyValues(wordCounts), ToHBase.hfile(outputPath));

    PipelineResult result = pipeline.run();
    assertTrue(result.succeeded());

    FileSystem fs = FileSystem.get(HBASE_TEST_UTILITY.getConfiguration());
    KeyValue kv = readFromHFiles(fs, outputPath, "and");
    assertEquals(427L, Bytes.toLong(kv.getValue()));
  }

  @Test
  public void testBulkLoad() throws Exception {
    Pipeline pipeline = new MRPipeline(HFileTargetIT.class, HBASE_TEST_UTILITY.getConfiguration());
    Path inputPath = copyResourceFileToHDFS("shakes.txt");
    Path outputPath = getTempPathOnHDFS("out");
    byte[] columnFamilyA = Bytes.toBytes("colfamA");
    byte[] columnFamilyB = Bytes.toBytes("colfamB");
    HTable testTable = createTable(26, new HColumnDescriptor(columnFamilyA), new HColumnDescriptor(columnFamilyB));
    PCollection<String> shakespeare = pipeline.read(At.textFile(inputPath, Writables.strings()));
    PCollection<String> words = split(shakespeare, "\\s+");
    PTable<String,Long> wordCounts = words.count();
    PCollection<Put> wordCountPuts = convertToPuts(wordCounts, columnFamilyA, columnFamilyB);
    HFileUtils.writePutsToHFilesForIncrementalLoad(
        wordCountPuts,
        testTable,
        outputPath);

    PipelineResult result = pipeline.run();
    assertTrue(result.succeeded());

    new LoadIncrementalHFiles(HBASE_TEST_UTILITY.getConfiguration())
        .doBulkLoad(outputPath, testTable);

    Map<String, Long> EXPECTED = ImmutableMap.<String, Long>builder()
        .put("__EMPTY__", 1470L)
        .put("the", 620L)
        .put("and", 427L)
        .put("of", 396L)
        .put("to", 367L)
        .build();

    for (Map.Entry<String, Long> e : EXPECTED.entrySet()) {
      assertEquals((long) e.getValue(), getWordCountFromTable(testTable, columnFamilyA, e.getKey()));
      assertEquals((long) e.getValue(), getWordCountFromTable(testTable, columnFamilyB, e.getKey()));
    }
  }

  /** See CRUNCH-251 */
  @Test
  public void testMultipleHFileTargets() throws Exception {
    Pipeline pipeline = new MRPipeline(HFileTargetIT.class, HBASE_TEST_UTILITY.getConfiguration());
    Path inputPath = copyResourceFileToHDFS("shakes.txt");
    Path outputPath1 = getTempPathOnHDFS("out1");
    Path outputPath2 = getTempPathOnHDFS("out2");
    HTable table1 = createTable(26);
    HTable table2 = createTable(26);
    LoadIncrementalHFiles loader = new LoadIncrementalHFiles(HBASE_TEST_UTILITY.getConfiguration());
    boolean onlyAffectedRegions = true;

    PCollection<String> shakespeare = pipeline.read(At.textFile(inputPath, Writables.strings()));
    PCollection<String> words = split(shakespeare, "\\s+");
    PCollection<String> shortWords = words.filter(SHORT_WORD_FILTER);
    PCollection<String> longWords = words.filter(FilterFns.not(SHORT_WORD_FILTER));
    PTable<String, Long> shortWordCounts = shortWords.count();
    PTable<String, Long> longWordCounts = longWords.count();
    HFileUtils.writePutsToHFilesForIncrementalLoad(
        convertToPuts(shortWordCounts),
        table1,
        outputPath1);
    HFileUtils.writePutsToHFilesForIncrementalLoad(
        convertToPuts(longWordCounts),
        table2,
        outputPath2,
        onlyAffectedRegions);

    PipelineResult result = pipeline.run();
    assertTrue(result.succeeded());

    loader.doBulkLoad(outputPath1, table1);
    loader.doBulkLoad(outputPath2, table2);

    assertEquals(396L, getWordCountFromTable(table1, "of"));
    assertEquals(427L, getWordCountFromTable(table2, "and"));
  }

  @Test
  public void testHFileUsesFamilyConfig() throws Exception {
    DataBlockEncoding newBlockEncoding = DataBlockEncoding.PREFIX;
    assertNotSame(newBlockEncoding, DataBlockEncoding.valueOf(HColumnDescriptor.DEFAULT_DATA_BLOCK_ENCODING));

    Pipeline pipeline = new MRPipeline(HFileTargetIT.class, HBASE_TEST_UTILITY.getConfiguration());
    Path inputPath = copyResourceFileToHDFS("shakes.txt");
    Path outputPath = getTempPathOnHDFS("out");
    HColumnDescriptor hcol = new HColumnDescriptor(TEST_FAMILY);
    hcol.setDataBlockEncoding(newBlockEncoding);
    HTable testTable = createTable(26, hcol);

    PCollection<String> shakespeare = pipeline.read(At.textFile(inputPath, Writables.strings()));
    PCollection<String> words = split(shakespeare, "\\s+");
    PTable<String,Long> wordCounts = words.count();
    PCollection<Put> wordCountPuts = convertToPuts(wordCounts);
    HFileUtils.writePutsToHFilesForIncrementalLoad(
        wordCountPuts,
        testTable,
        outputPath);

    PipelineResult result = pipeline.run();
    assertTrue(result.succeeded());

    int hfilesCount = 0;
    Configuration conf = HBASE_TEST_UTILITY.getConfiguration();
    FileSystem fs = outputPath.getFileSystem(conf);
    for (FileStatus e : fs.listStatus(new Path(outputPath, Bytes.toString(TEST_FAMILY)))) {
      Path f = e.getPath();
      if (!f.getName().startsWith("part-")) { // filter out "_SUCCESS"
        continue;
      }
      HFile.Reader reader = null;
      try {
        reader = HFile.createReader(fs, f, new CacheConfig(conf), conf);
        assertEquals(DataBlockEncoding.PREFIX, reader.getDataBlockEncoding());
      } finally {
        if (reader != null) {
          reader.close();
        }
      }
      hfilesCount++;
    }
    assertTrue(hfilesCount > 0);
  }

  /**
   * @see <a href='https://issues.apache.org/jira/browse/CRUNCH-588'>CRUNCH-588</a>
     */
  @Test
  public void testOnlyAffectedRegionsWhenWritingHFiles() throws Exception {
    Pipeline pipeline = new MRPipeline(HFileTargetIT.class, HBASE_TEST_UTILITY.getConfiguration());
    Path inputPath = copyResourceFileToHDFS("shakes.txt");
    Path outputPath1 = getTempPathOnHDFS("out1");
    HTable table1 = createTable(26);

    PCollection<String> shakespeare = pipeline.read(At.textFile(inputPath, Writables.strings()));
    PCollection<String> words = split(shakespeare, "\\s+");
    // take the top 5 here to reduce the number of affected regions in the table
    PTable<String, Long> count = words.filter(SHORT_WORD_FILTER).count().top(5);
    boolean onlyAffectedRegions = true;

    PCollection<Put> wordPuts = convertToPuts(count);
    HFileUtils.writePutsToHFilesForIncrementalLoad(
            wordPuts,
            table1,
            outputPath1,
            onlyAffectedRegions);

    // locate partition file directory and read it in to verify
    // the number of regions to be written to are less than the
    // number of regions in the table
    String tempPath = ((DistributedPipeline) pipeline).createTempPath().toString();
    Path temp = new Path(tempPath.substring(0, tempPath.lastIndexOf("/")));
    FileSystem fs = FileSystem.get(pipeline.getConfiguration());
    Path partitionPath = null;

    for (final FileStatus fileStatus : fs.listStatus(temp)) {
      RemoteIterator<LocatedFileStatus> remoteFIles = fs.listFiles(fileStatus.getPath(), true);

      while(remoteFIles.hasNext()) {
        LocatedFileStatus file = remoteFIles.next();
        if(file.getPath().toString().contains("partition")) {
          partitionPath = file.getPath();
          System.out.println("found written partitions in path: " + partitionPath.toString());
          break;
        }
      }

      if(partitionPath != null) {
        break;
      }
    }

    if(partitionPath == null) {
      throw new AssertionError("Partition path was not found");
    }

    Class<BytesWritable> keyClass = BytesWritable.class;
    List<BytesWritable> writtenPartitions = new ArrayList<>();
    WritableDeepCopier wdc = new WritableDeepCopier(keyClass);
    SeqFileReaderFactory<BytesWritable> s = new SeqFileReaderFactory<>(keyClass);

    // read back in the partition file
    Iterator<BytesWritable> iter = CompositePathIterable.create(fs, partitionPath, s).iterator();
    while (iter.hasNext()) {
      BytesWritable next = iter.next();
      writtenPartitions.add((BytesWritable) wdc.deepCopy(next));
    }

    ImmutableList<byte[]> startKeys = ImmutableList.copyOf(table1.getStartKeys());
    // assert that only affected regions were loaded into
    assertTrue(startKeys.size() > writtenPartitions.size());

    // write out and read back in the start keys for each region.
    // do this to get proper byte alignment
    Path regionStartKeys = tmpDir.getPath("regionStartKeys");
    List<KeyValue> startKeysToWrite = Lists.newArrayList();
    for (final byte[] startKey : startKeys.subList(1, startKeys.size())) {
      startKeysToWrite.add(KeyValueUtil.createFirstOnRow(startKey));
    }
    writeToSeqFile(pipeline.getConfiguration(), regionStartKeys, startKeysToWrite);

    List<BytesWritable> writtenStartKeys = new ArrayList<>();
    iter = CompositePathIterable.create(fs, partitionPath, s).iterator();
    while (iter.hasNext()) {
      BytesWritable next = iter.next();
      writtenStartKeys.add((BytesWritable) wdc.deepCopy(next));
    }

    // assert the keys read back in match start keys for a region on the table
    for (final BytesWritable writtenPartition : writtenPartitions) {
      boolean foundMatchingKv = false;
      for (final BytesWritable writtenStartKey : writtenStartKeys) {
        if (writtenStartKey.equals(writtenPartition)) {
          foundMatchingKv = true;
          break;
        }
      }

      if(!foundMatchingKv) {
        throw new AssertionError("Written KeyValue: " + writtenPartition + " did not match any known start keys of the table");
      }
    }

    pipeline.done();
  }

  private static void writeToSeqFile(
          Configuration conf,
          Path path,
          List<KeyValue> splitPoints) throws IOException {
    SequenceFile.Writer writer = SequenceFile.createWriter(
            path.getFileSystem(conf),
            conf,
            path,
            NullWritable.class,
            BytesWritable.class);
    for (KeyValue key : splitPoints) {
      writer.append(NullWritable.get(), HBaseTypes.keyValueToBytes(key));
    }
    writer.close();
  }

  private static PCollection<Put> convertToPuts(PTable<String, Long> in) {
    return convertToPuts(in, TEST_FAMILY);
  }

  private static PCollection<Put> convertToPuts(PTable<String, Long> in, final byte[]...columnFamilies) {
    return in.parallelDo(new MapFn<Pair<String, Long>, Put>() {
      @Override
      public Put map(Pair<String, Long> input) {
        String w = input.first();
        if (w.length() == 0) {
          w = "__EMPTY__";
        }
        long c = input.second();
        Put p = new Put(Bytes.toBytes(w));
        for (byte[] columnFamily : columnFamilies) {
          p.add(columnFamily, TEST_QUALIFIER, Bytes.toBytes(c));
        }
        return p;
      }
    }, HBaseTypes.puts());
  }

  private static PCollection<KeyValue> convertToKeyValues(PTable<String, Long> in) {
    return in.parallelDo(new MapFn<Pair<String, Long>, Pair<KeyValue, Void>>() {
      @Override
      public Pair<KeyValue, Void> map(Pair<String, Long> input) {
        String w = input.first();
        if (w.length() == 0) {
          w = "__EMPTY__";
        }
        long c = input.second();
        Cell cell = CellUtil.createCell(Bytes.toBytes(w), Bytes.toBytes(c));
        return Pair.of(KeyValue.cloneAndAddTags(cell, ImmutableList.<Tag>of()), null);
      }
    }, tableOf(HBaseTypes.keyValues(), nulls()))
        .groupByKey(GroupingOptions.builder()
            .sortComparatorClass(HFileUtils.KeyValueComparator.class)
            .build())
        .ungroup()
        .keys();
  }

  private static PCollection<String> split(PCollection<String> in, final String regex) {
    return in.parallelDo(new DoFn<String, String>() {
      @Override
      public void process(String input, Emitter<String> emitter) {
        for (String w : input.split(regex)) {
          emitter.emit(w);
        }
      }
    }, Writables.strings());
  }

  /** Reads the first value on a given row from a bunch of hfiles. */
  private static KeyValue readFromHFiles(FileSystem fs, Path mrOutputPath, String row) throws IOException {
    List<KeyValueScanner> scanners = Lists.newArrayList();
    KeyValue fakeKV = KeyValue.createFirstOnRow(Bytes.toBytes(row));
    for (FileStatus e : fs.listStatus(mrOutputPath)) {
      Path f = e.getPath();
      if (!f.getName().startsWith("part-")) { // filter out "_SUCCESS"
        continue;
      }
      StoreFile.Reader reader = new StoreFile.Reader(
          fs,
          f,
          new CacheConfig(fs.getConf()),
          fs.getConf());
      StoreFileScanner scanner = reader.getStoreFileScanner(false, false);
      scanner.seek(fakeKV); // have to call seek of each underlying scanner, otherwise KeyValueHeap won't work
      scanners.add(scanner);
    }
    assertTrue(!scanners.isEmpty());
    KeyValueScanner kvh = new KeyValueHeap(scanners, KeyValue.COMPARATOR);
    boolean seekOk = kvh.seek(fakeKV);
    assertTrue(seekOk);
    Cell kv = kvh.next();
    kvh.close();
    return KeyValue.cloneAndAddTags(kv, ImmutableList.<Tag>of());
  }

  private static Path copyResourceFileToHDFS(String resourceName) throws IOException {
    Configuration conf = HBASE_TEST_UTILITY.getConfiguration();
    FileSystem fs = FileSystem.get(conf);
    Path resultPath = getTempPathOnHDFS(resourceName);
    InputStream in = null;
    OutputStream out = null;
    try {
      in = Resources.getResource(resourceName).openConnection().getInputStream();
      out = fs.create(resultPath);
      IOUtils.copy(in, out);
    } finally {
      IOUtils.closeQuietly(in);
      IOUtils.closeQuietly(out);
    }
    return resultPath;
  }

  private static Path getTempPathOnHDFS(String fileName) throws IOException {
    Configuration conf = HBASE_TEST_UTILITY.getConfiguration();
    FileSystem fs = FileSystem.get(conf);
    Path result = new Path(TEMP_DIR, fileName);
    return result.makeQualified(fs);
  }

  private static long getWordCountFromTable(HTable table, String word) throws IOException {
    return getWordCountFromTable(table, TEST_FAMILY, word);
  }

  private static long getWordCountFromTable(HTable table, byte[] columnFamily, String word) throws IOException {
    Get get = new Get(Bytes.toBytes(word));
    get.addFamily(columnFamily);
    byte[] value = table.get(get).value();
    if (value == null) {
      fail("no such row: " +  word);
    }
    return Bytes.toLong(value);
  }
}

