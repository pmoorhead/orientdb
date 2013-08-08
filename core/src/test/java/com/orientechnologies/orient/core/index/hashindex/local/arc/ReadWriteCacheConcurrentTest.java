package com.orientechnologies.orient.core.index.hashindex.local.arc;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

import com.orientechnologies.common.directmemory.ODirectMemory;
import com.orientechnologies.common.directmemory.ODirectMemoryFactory;
import com.orientechnologies.common.serialization.types.OIntegerSerializer;
import com.orientechnologies.common.serialization.types.OLongSerializer;
import com.orientechnologies.orient.core.Orient;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.index.hashindex.local.cache.OCachePointer;
import com.orientechnologies.orient.core.index.hashindex.local.cache.OReadWriteDiskCache;
import com.orientechnologies.orient.core.storage.fs.OFileClassic;
import com.orientechnologies.orient.core.storage.impl.local.paginated.OLocalPaginatedStorage;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * @author Artem Loginov
 */
@Test
public class ReadWriteCacheConcurrentTest {
  private final int                                  systemOffset    = 2 * (OIntegerSerializer.INT_SIZE + OLongSerializer.LONG_SIZE);

  private static final int                           THREAD_COUNT    = 4;
  private static final int                           PAGE_COUNT      = 20;
  private static final int                           FILE_COUNT      = 8;
  private OReadWriteDiskCache                        buffer;
  private OLocalPaginatedStorage                     storageLocal;
  private ODirectMemory                              directMemory;

  private String[]                                   fileNames;
  private byte                                       seed;
  private final ExecutorService                      executorService = Executors.newFixedThreadPool(THREAD_COUNT);
  private final List<Future<Void>>                   futures         = new ArrayList<Future<Void>>(THREAD_COUNT);
  private AtomicLongArray                            fileIds         = new AtomicLongArray(FILE_COUNT);
  private AtomicIntegerArray                         pageCounters    = new AtomicIntegerArray(FILE_COUNT);
  private final AtomicReferenceArray<Queue<Integer>> pagesQueue      = new AtomicReferenceArray<Queue<Integer>>(FILE_COUNT);

  private AtomicBoolean                              continuousWrite = new AtomicBoolean(true);
  private AtomicInteger                              version         = new AtomicInteger(1);

  @BeforeClass
  public void beforeClass() throws IOException {

    OGlobalConfiguration.FILE_LOCK.setValue(Boolean.FALSE);
    directMemory = ODirectMemoryFactory.INSTANCE.directMemory();

    String buildDirectory = System.getProperty("buildDirectory");
    if (buildDirectory == null)
      buildDirectory = ".";

    storageLocal = (OLocalPaginatedStorage) Orient.instance().loadStorage(
        "plocal:" + buildDirectory + "/ReadWriteCacheConcurrentTest");

    prepareFilesForTest(FILE_COUNT);

  }

  private void prepareFilesForTest(int filesCount) {
    fileNames = new String[filesCount];
    for (int i = 0; i < fileNames.length; i++) {
      fileNames[i] = "readWriteCacheTest" + i + ".tst";
    }
  }

  @BeforeMethod
  public void beforeMethod() throws IOException {
    if (buffer != null) {
      buffer.close();

      deleteUsedFiles(FILE_COUNT);
    }

    initBuffer();

    Random random = new Random();
    seed = (byte) (random.nextInt() & 0xFF);
  }

  private void initBuffer() throws IOException {
    buffer = new OReadWriteDiskCache(4 * (8 + systemOffset), 15000 * (8 + systemOffset), 8 + systemOffset, 10000, -1, storageLocal,
        null, true, false);
  }

  @AfterClass
  public void afterClass() throws IOException {
    buffer.close();
    storageLocal.delete();

    deleteUsedFiles(FILE_COUNT);
  }

  private void deleteUsedFiles(int filesCount) {
    for (int k = 0; k < filesCount; k++) {
      File file = new File(storageLocal.getConfiguration().getDirectory() + "/readWriteCacheTest" + k + ".tst");
      if (file.exists())
        Assert.assertTrue(file.delete());
    }
  }

  public void testAdd() throws Exception {
    getIdentitiesOfFiles();

    fillFilesWithContent();

    validateFilesContent(version.byteValue());

    version.compareAndSet(1, 2);
    continuousWrite.compareAndSet(true, false);

    generateRemainingPagesQueueForAllFiles();

    executeConcurrentRandomReadAndWriteOperations();

    buffer.flushBuffer();

    validateFilesContent(version.byteValue());
  }

  private void executeConcurrentRandomReadAndWriteOperations() throws InterruptedException, ExecutionException {
    for (int i = 0; i < THREAD_COUNT; i++) {
      futures.add(executorService.submit(new Writer()));
    }
    for (int i = 0; i < THREAD_COUNT; i++) {
      futures.add(executorService.submit(new Reader()));
    }

    for (Future<Void> future : futures)
      future.get();
  }

  private void generateRemainingPagesQueueForAllFiles() {
    List<Integer>[] array = new ArrayList[FILE_COUNT];
    for (int k = 0; k < FILE_COUNT; ++k) {
      array[k] = new ArrayList<Integer>(PAGE_COUNT);
      for (Integer i = 0; i < PAGE_COUNT; ++i) {
        array[k].add(i);
      }
    }

    for (int i = 0; i < FILE_COUNT; ++i) {
      Collections.shuffle(array[i]);
      pagesQueue.set(i, new ConcurrentLinkedQueue<Integer>(array[i]));
    }
  }

  private void fillFilesWithContent() throws InterruptedException, ExecutionException, IOException {
    for (int i = 0; i < THREAD_COUNT; i++) {
      futures.add(executorService.submit(new Writer()));
    }

    for (Future<Void> future : futures)
      future.get();

    futures.clear();

    buffer.flushBuffer();
  }

  private void getIdentitiesOfFiles() throws IOException {
    for (int i = 0; i < fileIds.length(); i++) {
      fileIds.set(i, buffer.openFile(fileNames[i]));
    }
  }

  private void validateFilesContent(byte version) throws IOException {
    for (int k = 0; k < FILE_COUNT; ++k) {
      validateFileContent(version, k);
    }
  }

  private void validateFileContent(byte version, int k) throws IOException {
    String path = storageLocal.getConfiguration().getDirectory() + "/readWriteCacheTest" + k + ".tst";

    OFileClassic fileClassic = new OFileClassic();
    fileClassic.init(path, "r");
    fileClassic.open();

    for (int i = 0; i < PAGE_COUNT; i++) {
      byte[] content = new byte[8];
      fileClassic.read(i * (8 + systemOffset) + systemOffset, content, 8);

      Assert.assertEquals(content, new byte[] { version, 2, 3, seed, 5, 6, (byte) k, (byte) (i & 0xFF) }, " i = " + i);
    }
    fileClassic.close();
  }

  private class Writer implements Callable<Void> {
    @Override
    public Void call() throws Exception {
      int fileNumber = getNextFileNumber();
      while (shouldContinue(fileNumber)) {
        final long pageIndex = getNextPageIndex(fileNumber);
        if (pageIndex >= 0) {
          writeToFile(fileNumber, pageIndex);
        }
        fileNumber = getNextFileNumber();
      }
      return null;
    }

    private void writeToFile(int fileNumber, long pageIndex) throws IOException {
      OCachePointer pointer = buffer.load(fileIds.get(fileNumber), pageIndex);
      pointer.acquireExclusiveLock();
      buffer.markDirty(fileIds.get(fileNumber), pageIndex);

      directMemory.set(pointer.getDataPointer() + systemOffset, new byte[] { version.byteValue(), 2, 3, seed, 5, 6,
          (byte) fileNumber, (byte) (pageIndex & 0xFF) }, 0, 8);
      pointer.releaseExclusiveLock();
      buffer.release(fileIds.get(fileNumber), pageIndex);
    }

    private long getNextPageIndex(int fileNumber) {
      if (continuousWrite.get()) {
        return pageCounters.getAndIncrement(fileNumber);
      } else {
        final Integer pageIndex = pagesQueue.get(fileNumber).poll();

        if (pageIndex == null) {
          return -1;
        } else {
          return pageIndex;
        }
      }
    }

    private boolean shouldContinue(int fileNumber) {
      return fileNumber != -1;
    }

    public int getNextFileNumber() {
      int firstFileNumber = new Random().nextInt(FILE_COUNT - 1);
      for (int i = 0; i < FILE_COUNT; ++i) {
        int fileNumber = (firstFileNumber + i) % FILE_COUNT;
        if (isFileFull(fileNumber))
          return fileNumber;
      }
      return -1;
    }

    private boolean isFileFull(int fileNumber) {
      if (continuousWrite.get()) {
        return pageCounters.get(fileNumber) < PAGE_COUNT;
      } else {
        return !pagesQueue.get(fileNumber).isEmpty();
      }
    }
  }

  private class Reader implements Callable<Void> {
    @Override
    public Void call() throws Exception {
      long pageIndex = Math.abs(new Random().nextInt() % PAGE_COUNT);
      int fileNumber = new Random().nextInt(FILE_COUNT);

      OCachePointer pointer = buffer.load(fileIds.get(fileNumber), pageIndex);

      byte[] content = directMemory.get(pointer.getDataPointer() + systemOffset, 8);

      buffer.release(fileIds.get(fileNumber), pageIndex);

      Assert.assertTrue(content[0] == 1 || content[0] == 2);
      Assert.assertEquals(content, new byte[] { content[0], 2, 3, seed, 5, 6, (byte) fileNumber, (byte) (pageIndex & 0xFF) });
      return null;
    }
  }
}
