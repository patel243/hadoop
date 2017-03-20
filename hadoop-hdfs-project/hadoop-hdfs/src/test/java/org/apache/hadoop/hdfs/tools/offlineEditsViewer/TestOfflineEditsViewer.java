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

package org.apache.hadoop.hdfs.tools.offlineEditsViewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.server.namenode.FSEditLogOpCodes;
import org.apache.hadoop.hdfs.server.namenode.NameNodeLayoutVersion;
import org.apache.hadoop.hdfs.server.namenode.OfflineEditsViewerHelper;
import org.apache.hadoop.hdfs.tools.offlineEditsViewer.OfflineEditsViewer.Flags;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.test.PathUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.common.collect.ImmutableSet;

public class TestOfflineEditsViewer {
  private static final Log LOG = LogFactory
      .getLog(TestOfflineEditsViewer.class);

  private static final String buildDir = PathUtils
      .getTestDirName(TestOfflineEditsViewer.class);

  // to create edits and get edits filename
  private static final OfflineEditsViewerHelper nnHelper = new OfflineEditsViewerHelper();
  private static final ImmutableSet<FSEditLogOpCodes> skippedOps = skippedOps();

  @SuppressWarnings("deprecation")
  private static ImmutableSet<FSEditLogOpCodes> skippedOps() {
    ImmutableSet.Builder<FSEditLogOpCodes> b = ImmutableSet.builder();

    // Deprecated opcodes
    b.add(FSEditLogOpCodes.OP_DATANODE_ADD)
        .add(FSEditLogOpCodes.OP_DATANODE_REMOVE)
        .add(FSEditLogOpCodes.OP_SET_NS_QUOTA)
        .add(FSEditLogOpCodes.OP_CLEAR_NS_QUOTA)
        .add(FSEditLogOpCodes.OP_SET_GENSTAMP_V1);

    // Cannot test delegation token related code in insecure set up
    b.add(FSEditLogOpCodes.OP_GET_DELEGATION_TOKEN)
        .add(FSEditLogOpCodes.OP_RENEW_DELEGATION_TOKEN)
        .add(FSEditLogOpCodes.OP_CANCEL_DELEGATION_TOKEN);

    // Skip invalid opcode
    b.add(FSEditLogOpCodes.OP_INVALID);
    return b.build();
  }

  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();

  @Before
  public void setUp() throws IOException {
    nnHelper.startCluster(buildDir + "/dfs/");
  }

  @After
  public void tearDown() throws IOException {
    nnHelper.shutdownCluster();
  }

  /**
   * Test the OfflineEditsViewer
   */
  @Test
  public void testGenerated() throws IOException {
    // edits generated by nnHelper (MiniDFSCluster), should have all op codes
    // binary, XML, reparsed binary
    String edits = nnHelper.generateEdits();
    LOG.info("Generated edits=" + edits);
    String editsParsedXml = folder.newFile("editsParsed.xml").getAbsolutePath();
    String editsReparsed = folder.newFile("editsParsed").getAbsolutePath();
    // capital case extension
    String editsParsedXML_caseInSensitive =
        folder.newFile("editsRecoveredParsed.XML").getAbsolutePath();

    // parse to XML then back to binary
    assertEquals(0, runOev(edits, editsParsedXml, "xml", false));
    assertEquals(0, runOev(edits, editsParsedXML_caseInSensitive, "xml", false));
    assertEquals(0, runOev(editsParsedXml, editsReparsed, "binary", false));
    assertEquals(0,
        runOev(editsParsedXML_caseInSensitive, editsReparsed, "binary", false));


    // judgment time
    assertTrue("Edits " + edits + " should have all op codes",
        hasAllOpCodes(edits));
    LOG.info("Comparing generated file " + editsReparsed
        + " with reference file " + edits);
    assertTrue(
        "Generated edits and reparsed (bin to XML to bin) should be same",
        filesEqualIgnoreTrailingZeros(edits, editsReparsed));
  }


  @Test
  public void testRecoveryMode() throws IOException {
    // edits generated by nnHelper (MiniDFSCluster), should have all op codes
    // binary, XML, reparsed binary
    String edits = nnHelper.generateEdits();
    FileOutputStream os = new FileOutputStream(edits, true);
    // Corrupt the file by truncating the end
    FileChannel editsFile = os.getChannel();
    editsFile.truncate(editsFile.size() - 5);

    String editsParsedXml = folder.newFile("editsRecoveredParsed.xml")
        .getAbsolutePath();
    String editsReparsed = folder.newFile("editsRecoveredReparsed")
        .getAbsolutePath();
    String editsParsedXml2 = folder.newFile("editsRecoveredParsed2.xml")
        .getAbsolutePath();

    // Can't read the corrupted file without recovery mode
    assertEquals(-1, runOev(edits, editsParsedXml, "xml", false));

    // parse to XML then back to binary
    assertEquals(0, runOev(edits, editsParsedXml, "xml", true));
    assertEquals(0, runOev(editsParsedXml, editsReparsed, "binary", false));
    assertEquals(0, runOev(editsReparsed, editsParsedXml2, "xml", false));

    // judgment time
    assertTrue("Test round trip", FileUtils.contentEqualsIgnoreEOL(
        new File(editsParsedXml), new File(editsParsedXml2), "UTF-8"));

    os.close();
  }

  @Test
  public void testStored() throws IOException {
    // reference edits stored with source code (see build.xml)
    final String cacheDir = System.getProperty("test.cache.data",
        "build/test/cache");
    // binary, XML, reparsed binary
    String editsStored = cacheDir + "/editsStored";
    String editsStoredParsedXml = cacheDir + "/editsStoredParsed.xml";
    String editsStoredReparsed = cacheDir + "/editsStoredReparsed";
    // reference XML version of editsStored (see build.xml)
    String editsStoredXml = cacheDir + "/editsStored.xml";

    // parse to XML then back to binary
    assertEquals(0, runOev(editsStored, editsStoredParsedXml, "xml", false));
    assertEquals(0,
        runOev(editsStoredParsedXml, editsStoredReparsed, "binary", false));

    // judgement time
    assertTrue("Edits " + editsStored + " should have all op codes",
        hasAllOpCodes(editsStored));
    assertTrue("Reference XML edits and parsed to XML should be same",
        FileUtils.contentEqualsIgnoreEOL(new File(editsStoredXml),
          new File(editsStoredParsedXml), "UTF-8"));
    assertTrue(
        "Reference edits and reparsed (bin to XML to bin) should be same",
        filesEqualIgnoreTrailingZeros(editsStored, editsStoredReparsed));
  }

  /**
   * Run OfflineEditsViewer
   *
   * @param inFilename input edits filename
   * @param outFilename oputput edits filename
   */
  private int runOev(String inFilename, String outFilename, String processor,
      boolean recovery) throws IOException {

    LOG.info("Running oev [" + inFilename + "] [" + outFilename + "]");

    OfflineEditsViewer oev = new OfflineEditsViewer();
    Flags flags = new Flags();
    flags.setPrintToScreen();
    if (recovery) {
      flags.setRecoveryMode();
    }
    return oev.go(inFilename, outFilename, processor, flags, null);
  }

  /**
   * Checks that the edits file has all opCodes
   *
   * @param filename edits file
   * @return true is edits (filename) has all opCodes
   */
  private boolean hasAllOpCodes(String inFilename) throws IOException {
    String outFilename = inFilename + ".stats";
    FileOutputStream fout = new FileOutputStream(outFilename);
    StatisticsEditsVisitor visitor = new StatisticsEditsVisitor(fout);
    OfflineEditsViewer oev = new OfflineEditsViewer();
    if (oev.go(inFilename, outFilename, "stats", new Flags(), visitor) != 0)
      return false;
    LOG.info("Statistics for " + inFilename + "\n"
        + visitor.getStatisticsString());

    boolean hasAllOpCodes = true;
    for (FSEditLogOpCodes opCode : FSEditLogOpCodes.values()) {
      // don't need to test obsolete opCodes
      if (skippedOps.contains(opCode))
        continue;

      Long count = visitor.getStatistics().get(opCode);
      if ((count == null) || (count == 0)) {
        hasAllOpCodes = false;
        LOG.info("Opcode " + opCode + " not tested in " + inFilename);
      }
    }
    return hasAllOpCodes;
  }

  /**
   * Compare two files, ignore trailing zeros at the end, for edits log the
   * trailing zeros do not make any difference, throw exception is the files are
   * not same
   *
   * @param filenameSmall first file to compare (doesn't have to be smaller)
   * @param filenameLarge second file to compare (doesn't have to be larger)
   */
  private boolean filesEqualIgnoreTrailingZeros(String filenameSmall,
    String filenameLarge) throws IOException {

    ByteBuffer small = ByteBuffer.wrap(DFSTestUtil.loadFile(filenameSmall));
    ByteBuffer large = ByteBuffer.wrap(DFSTestUtil.loadFile(filenameLarge));
    // OEV outputs with the latest layout version, so tweak the old file's
    // contents to have latest version so checkedin binary files don't
    // require frequent updates
    small.put(3, (byte)NameNodeLayoutVersion.CURRENT_LAYOUT_VERSION);

    // now correct if it's otherwise
    if (small.capacity() > large.capacity()) {
      ByteBuffer tmpByteBuffer = small;
      small = large;
      large = tmpByteBuffer;
      String tmpFilename = filenameSmall;
      filenameSmall = filenameLarge;
      filenameLarge = tmpFilename;
    }

    // compare from 0 to capacity of small
    // the rest of the large should be all zeros
    small.position(0);
    small.limit(small.capacity());
    large.position(0);
    large.limit(small.capacity());

    // compares position to limit
    if (!small.equals(large)) {
      return false;
    }

    // everything after limit should be 0xFF
    int i = large.limit();
    large.clear();
    for (; i < large.capacity(); i++) {
      if (large.get(i) != FSEditLogOpCodes.OP_INVALID.getOpCode()) {
        return false;
      }
    }

    return true;
  }

  @Test
  public void testOfflineEditsViewerHelpMessage() throws Throwable {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final PrintStream out = new PrintStream(bytes);
    final PrintStream oldOut = System.out;
    try {
      System.setOut(out);
      int status = new OfflineEditsViewer().run(new String[] { "-h" });
      assertTrue("" + "Exit code returned for help option is incorrect",
          status == 0);
      Assert.assertFalse(
          "Invalid Command error displayed when help option is passed.", bytes
              .toString().contains("Error parsing command-line options"));
    } finally {
      System.setOut(oldOut);
      IOUtils.closeStream(out);
    }
  }

  @Test
  public void testStatisticsStrWithNullOpCodeCount() throws IOException {
    String editFilename = nnHelper.generateEdits();
    String outFilename = editFilename + ".stats";
    FileOutputStream fout = new FileOutputStream(outFilename);
    StatisticsEditsVisitor visitor = new StatisticsEditsVisitor(fout);
    OfflineEditsViewer oev = new OfflineEditsViewer();

    String statisticsStr = null;
    if (oev.go(editFilename, outFilename, "stats", new Flags(), visitor) == 0) {
      statisticsStr = visitor.getStatisticsString();
    }
    Assert.assertNotNull(statisticsStr);

    String str;
    Long count;
    Map<FSEditLogOpCodes, Long> opCodeCount = visitor.getStatistics();
    for (FSEditLogOpCodes opCode : FSEditLogOpCodes.values()) {
      count = opCodeCount.get(opCode);
      // Verify the str when the opCode's count is null
      if (count == null) {
        str =
            String.format("    %-30.30s (%3d): %d%n", opCode.toString(),
                opCode.getOpCode(), Long.valueOf(0L));
        assertTrue(statisticsStr.contains(str));
      }
    }
  }

  @Test
  public void testProcessorWithSameTypeFormatFile() throws IOException {
    String edits = nnHelper.generateEdits();
    LOG.info("Generated edits=" + edits);
    String binaryEdits = folder.newFile("binaryEdits").getAbsolutePath();
    String editsParsedXml = folder.newFile("editsParsed.xml").getAbsolutePath();
    String editsReparsedXml = folder.newFile("editsReparsed.xml")
        .getAbsolutePath();

    // Binary format input file is not allowed to be processed
    // by Binary processor.
    assertEquals(-1, runOev(edits, binaryEdits, "binary", false));
    // parse to XML then back to XML
    assertEquals(0, runOev(edits, editsParsedXml, "xml", false));
    // XML format input file is not allowed to be processed by XML processor.
    assertEquals(-1, runOev(editsParsedXml, editsReparsedXml, "xml", false));
  }
}
