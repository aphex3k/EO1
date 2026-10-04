package com.aphex3k.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;

/**
 * Tests {@link ApkManifestInfo} against hand-built binary-XML (AXML) fixtures: the file
 * header, a minimal string pool, a root start-element chunk with embedded attribute records,
 * and the end-element chunk — the chunk layout aapt/aapt2 emit for {@code AndroidManifest.xml}.
 */
public class ApkManifestInfoTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final Charset UTF_16LE = Charset.forName("UTF-16LE");

    private static final int RES_TYPE_INT_DEC = 0x10;
    private static final int RES_TYPE_INT_HEX = 0x11;
    private static final int RES_TYPE_STRING = 0x03;
    /** Namespace form emitted by toolchains without a resource-id-map chunk. */
    private static final int NS_ANDROID = 0x01000000;
    private static final String ANDROID_NS_URI = "http://schemas.android.com/apk/res/android";

    // ---------------------------------------------------------------- success paths

    @Test
    public void utf16Pool_readsVersionCodeAndName() throws Exception {
        ApkManifestInfo info = infoFrom(buildAxml(
                new String[] {"manifest", "versionCode", "versionName", "1.7.0"},
                false, "manifest", 123, RES_TYPE_INT_DEC, "1.7.0", NS_ANDROID));
        assertNotNull(info);
        assertEquals(Integer.valueOf(123), info.versionCode);
        assertEquals("1.7.0", info.versionName);
    }

    @Test
    public void utf8Pool_readsVersionCodeAndName() throws Exception {
        // The 300-char string forces the 2-byte UTF-8 length prefix (>= 0x80 bytes).
        StringBuilder longStr = new StringBuilder("long-");
        for (int i = 0; i < 200; i++) {
            longStr.append((char) ('a' + i % 26));
        }
        ApkManifestInfo info = infoFrom(buildAxml(
                new String[] {longStr.toString(), "manifest", "versionCode", "versionName", "2.0"},
                true, "manifest", 7, RES_TYPE_INT_DEC, "2.0", NS_ANDROID));
        assertNotNull(info);
        assertEquals(Integer.valueOf(7), info.versionCode);
        assertEquals("2.0", info.versionName);
    }

    @Test
    public void intHexVersionCode_isAccepted() throws Exception {
        ApkManifestInfo info = infoFrom(buildAxml(
                new String[] {"manifest", "versionCode"},
                false, "manifest", 0x1234, RES_TYPE_INT_HEX, null, NS_ANDROID));
        assertNotNull(info);
        assertEquals(Integer.valueOf(0x1234), info.versionCode);
        assertEquals("", info.versionName);
    }

    @Test
    public void attributesBeyondVersionCodeAreIgnored() throws Exception {
        // A second android attribute that is neither versionCode nor versionName.
        ApkManifestInfo info = infoFrom(buildAxml(
                new String[] {"manifest", "versionCode", "minSdkVersion"},
                false, "manifest", 5, RES_TYPE_INT_DEC, null, NS_ANDROID,
                "minSdkVersion", RES_TYPE_INT_DEC, 21));
        assertNotNull(info);
        assertEquals(Integer.valueOf(5), info.versionCode);
        assertEquals("", info.versionName);
    }

    @Test
    public void androidNamespaceAsPoolIndex_isAccepted() throws Exception {
        // aapt2 names the android namespace by the pool index of its URI, not the magic value.
        ApkManifestInfo info = infoFrom(buildAxml(
                new String[] {ANDROID_NS_URI, "manifest", "versionCode", "versionName", "3.1"},
                false, "manifest", 21, RES_TYPE_INT_DEC, "3.1",
                0 /* pool index of the android namespace URI */));
        assertNotNull(info);
        assertEquals(Integer.valueOf(21), info.versionCode);
        assertEquals("3.1", info.versionName);
    }

    @Test
    public void zipWrappedStored_manifestIsReadFromApkLayout() throws Exception {
        byte[] axml = buildAxml(
                new String[] {"manifest", "versionCode", "versionName", "1.7.0"},
                false, "manifest", 42, RES_TYPE_INT_DEC, "1.7.0", NS_ANDROID);
        byte[] apk = buildZip(
                new String[] {"META-INF/MANIFEST.MF", "res/drawable/bg.png"},
                new byte[][] {"junk".getBytes(UTF_8), {0, 1, 2, 3}},
                "AndroidManifest.xml", axml);
        ApkManifestInfo info = infoFrom(apk);
        assertNotNull(info);
        assertEquals(Integer.valueOf(42), info.versionCode);
        assertEquals("1.7.0", info.versionName);
    }

    @Test
    public void realDebugApk_roundTrip() {
        // Exercises the parser against a real aapt-produced APK when a local build is
        // present; in CI (no build artifacts) the fixtures above cover the parser instead.
        File apk = new File("build/outputs/apk/debug/app-debug.apk");
        if (!apk.isFile()) {
            return;
        }
        ApkManifestInfo info = ApkManifestInfo.read(apk);
        assertNotNull(info);
        assertEquals(Integer.valueOf(com.aphex3k.eo1.BuildConfig.VERSION_CODE), info.versionCode);
        assertEquals(com.aphex3k.eo1.BuildConfig.VERSION_NAME, info.versionName);
    }

    // ---------------------------------------------------------------- failure paths

    @Test
    public void missingFile_failsClosed() {
        assertNull(ApkManifestInfo.read(null));
        assertNull(ApkManifestInfo.read(new File("no/such/file.axml")));
    }

    @Test
    public void plainText_failsClosed() throws Exception {
        byte[] junk = "this is definitely not a binary XML document, 0123456789"
                .getBytes(UTF_8);
        assertNull(infoFrom(junk));
    }

    @Test
    public void wrongFileHeader_failsClosed() throws Exception {
        byte[] axml = buildAxml(
                new String[] {"manifest", "versionCode"}, false, "manifest", 1,
                RES_TYPE_INT_DEC, null, NS_ANDROID);
        axml[0] = 0x04; // type 0x0004, not the AXML file header
        assertNull(infoFrom(axml));
    }

    @Test
    public void poolChunkMissing_failsClosed() throws Exception {
        byte[] axml = buildAxml(
                new String[] {"manifest", "versionCode"}, false, "manifest", 1,
                RES_TYPE_INT_DEC, null, NS_ANDROID);
        axml[8] = 0x02; // pool chunk type replaced with the attribute chunk type
        axml[9] = 0x01;
        assertNull(infoFrom(axml));
    }

    @Test
    public void truncated_failsClosed() throws Exception {
        byte[] axml = buildAxml(
                new String[] {"manifest", "versionCode"}, false, "manifest", 1,
                RES_TYPE_INT_DEC, null, NS_ANDROID);
        byte[] cut = new byte[40];
        System.arraycopy(axml, 0, cut, 0, 40);
        assertNull(infoFrom(cut));
    }

    @Test
    public void rootElementNotManifest_failsClosed() throws Exception {
        // versionCode lives under <application>, not the required root <manifest>.
        byte[] axml = buildAxml(
                new String[] {"manifest", "application", "versionCode"},
                false, "application", 42, RES_TYPE_INT_DEC, null, NS_ANDROID);
        assertNull(infoFrom(axml));
    }

    @Test
    public void versionCodeAsString_failsClosed() throws Exception {
        // Present but not a plain int: the parser must not guess.
        byte[] axml = buildAxml(
                new String[] {"manifest", "versionCode"}, false, "manifest",
                1 /* pool index, ignored */, RES_TYPE_STRING, null, NS_ANDROID);
        assertNull(infoFrom(axml));
    }

    @Test
    public void customNamespaceIgnored_failsClosed() throws Exception {
        byte[] axml = buildAxml(
                new String[] {"manifest", "versionCode"}, false, "manifest", 42,
                RES_TYPE_INT_DEC, null, 0x00000001 /* not the android namespace */);
        assertNull(infoFrom(axml));
    }

    @Test
    public void zipWithoutManifestEntry_failsClosed() throws Exception {
        byte[] apk = buildZip(
                new String[] {"classes.dex"},
                new byte[][] {{0, 1, 2, 3}},
                "AndroidManifest.xml", null /* no manifest entry */);
        assertNull(infoFrom(apk));
    }

    // ---------------------------------------------------------------- fixture building

    private static ApkManifestInfo infoFrom(byte[] axml) throws Exception {
        File f = File.createTempFile("axml", ".bin");
        f.deleteOnExit();
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(axml);
        } finally {
            out.close();
        }
        return ApkManifestInfo.read(f);
    }

    /**
     * A minimal AXML document: file header, string pool (UTF-16 or UTF-8), the root element's
     * start chunk with its attribute records embedded, and the end-element chunk. All fixture
     * strings are ASCII.
     *
     * @param versionCode null omits the attribute; otherwise written with {@code codeDataType}
     * @param versionName null omits the attribute; otherwise looked up in {@code strings}
     * @param ns the attribute namespace field (use {@link #NS_ANDROID} for accepted fixtures)
     */
    private static byte[] buildAxml(String[] strings, boolean utf8, String rootName,
            Integer versionCode, int codeDataType, String versionName, int ns) {
        return buildAxml(strings, utf8, rootName, versionCode, codeDataType, versionName, ns,
                null, 0, 0);
    }

    private static byte[] buildAxml(String[] strings, boolean utf8, String rootName,
            Integer versionCode, int codeDataType, String versionName, int ns,
            String extraAttrName, int extraAttrDataType, int extraAttrData) {
        int dataLen = 0;
        for (int i = 0; i < strings.length; i++) {
            int len = strings[i].length(); // fixtures are ASCII
            dataLen += utf8 ? (len < 0x80 ? 2 + len : 3 + len) : 4 + 2 * len;
        }
        int poolSize = 28 + strings.length * 4 + dataLen;
        int nAttrs = (versionCode != null ? 1 : 0)
                + (versionName != null ? 1 : 0)
                + (extraAttrName != null ? 1 : 0);
        int startElemSize = 36 + 20 * nAttrs;
        int endElemSize = 24;
        int total = 8 + poolSize + startElemSize + endElemSize;

        ByteBuf b = new ByteBuf();

        b.u16(0x0003); // file header
        b.u16(0x0008);
        b.u32(total);

        b.u16(0x0001); // string pool
        b.u16(0x001C);
        b.u32(poolSize);
        b.u32(strings.length);
        b.u32(0); // styles
        b.u32(utf8 ? 0x00100 : 0);
        b.u32(0x001C + strings.length * 4); // strings start
        b.u32(0); // styles start

        // Pool layout: the offsets array comes first, then the string data.
        int[] offsets = new int[strings.length];
        int offset = 0;
        for (int i = 0; i < strings.length; i++) {
            int len = strings[i].length(); // fixtures are ASCII: char count == UTF-8 byte count
            offsets[i] = offset;
            offset += utf8 ? (len < 0x80 ? 2 + len : 3 + len) : 4 + 2 * len;
        }
        for (int i = 0; i < strings.length; i++) {
            b.u32(offsets[i]);
        }
        for (int i = 0; i < strings.length; i++) {
            int len = strings[i].length();
            if (utf8) {
                if (len < 0x80) {
                    b.u8(len);
                } else {
                    b.u8(0x80 | (len >> 8));
                    b.u8(len & 0xFF);
                }
                b.write(strings[i].getBytes(UTF_8));
                b.u8(0);
            } else {
                b.u16(len);
                b.write(strings[i].getBytes(UTF_16LE));
                b.u16(0);
            }
        }

        b.u16(0x0102); // start element
        b.u16(0x0010);
        b.u32(startElemSize);
        b.u32(1); // line number
        b.u32(0xFFFFFFFFL); // no comment
        b.u32(0xFFFFFFFFL); // element not in a namespace
        b.u32(indexOf(strings, rootName));
        b.u16(0x0014); // attribute start (chunk-relative)
        b.u16(0x0014); // attribute record size
        b.u32(nAttrs);
        b.u32(0); // (padded)
        if (versionCode != null) {
            writeAttribute(b, ns, indexOf(strings, "versionCode"), codeDataType, versionCode);
        }
        if (versionName != null) {
            writeAttribute(b, ns, indexOf(strings, "versionName"), RES_TYPE_STRING,
                    indexOf(strings, versionName));
        }
        if (extraAttrName != null) {
            writeAttribute(b, ns, indexOf(strings, extraAttrName), extraAttrDataType,
                    extraAttrData);
        }

        b.u16(0x0103); // end element
        b.u16(0x0010);
        b.u32(endElemSize);
        b.u32(1); // line number
        b.u32(0xFFFFFFFFL); // no comment
        b.u32(0xFFFFFFFFL); // not in a namespace
        b.u32(indexOf(strings, rootName));

        return b.toBytes();
    }

    /** One embedded attribute record: namespace, name, raw value, Res_value. */
    private static void writeAttribute(ByteBuf b, int ns, int nameIdx, int dataType, int data) {
        b.u32(ns);
        b.u32(nameIdx);
        b.u32(0xFFFFFFFFL); // no raw value
        b.u16(8); // Res_value size
        b.u8(0); // res0
        b.u8(dataType);
        b.u32(data);
    }

    /**
     * A minimal well-formed zip with stored (uncompressed) entries: local file headers, then
     * the central directory, then the end-of-central-directory record.
     */
    private static byte[] buildZip(String[] fillerNames, byte[][] fillerData,
            String manifestName, byte[] manifestData) {
        int entryCount = fillerNames.length + (manifestData != null ? 1 : 0);
        String[] names = new String[entryCount];
        byte[][] data = new byte[entryCount][];
        int i = 0;
        for (int j = 0; j < fillerNames.length; j++) {
            names[i] = fillerNames[j];
            data[i] = fillerData[j];
            i++;
        }
        if (manifestData != null) {
            names[i] = manifestName;
            data[i] = manifestData;
        }

        ByteBuf b = new ByteBuf();
        int[] localOffsets = new int[entryCount];
        for (i = 0; i < entryCount; i++) {
            localOffsets[i] = b.size();
            b.u32(0x04034B50L); // local file header signature (PK\x03\x04)
            b.u16(20); // version needed
            b.u16(0); // flags
            b.u16(0); // method: stored
            b.u16(0); // mod time
            b.u16(0); // mod date
            b.u32(0); // crc (not verified by the reader)
            b.u32(data[i].length); // compressed size
            b.u32(data[i].length); // uncompressed size
            b.u16(names[i].length());
            b.u16(0); // extra field length
            b.write(names[i].getBytes(UTF_8));
            b.write(data[i]);
        }
        int cdOffset = b.size();
        for (i = 0; i < entryCount; i++) {
            byte[] nameBytes = names[i].getBytes(UTF_8);
            b.u32(0x02014B50L); // central directory signature
            b.u16(0x0314); // version made by (unix)
            b.u16(20); // version needed
            b.u16(0); // flags
            b.u16(0); // method: stored
            b.u16(0); // mod time
            b.u16(0); // mod date
            b.u32(0); // crc
            b.u32(data[i].length); // compressed size
            b.u32(data[i].length); // uncompressed size
            b.u16(nameBytes.length);
            b.u16(0); // extra field length
            b.u16(0); // file comment length
            b.u16(0); // disk number start
            b.u16(0); // internal attrs
            b.u32(0); // external attrs
            b.u32(localOffsets[i]);
            b.write(nameBytes);
        }
        int cdSize = b.size() - cdOffset;
        b.u32(0x06054B50L); // end of central directory signature
        b.u16(0); // disk number
        b.u16(0); // disk with central directory
        b.u16(entryCount);
        b.u16(entryCount);
        b.u32(cdSize);
        b.u32(cdOffset);
        b.u16(0); // comment length
        return b.toBytes();
    }

    private static int indexOf(String[] strings, String name) {
        for (int i = 0; i < strings.length; i++) {
            if (name.equals(strings[i])) {
                return i;
            }
        }
        throw new AssertionError("missing fixture string: " + name);
    }

    private static final class ByteBuf {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void u8(int v) {
            out.write(v & 0xFF);
        }

        void u16(int v) {
            u8(v & 0xFF);
            u8((v >>> 8) & 0xFF);
        }

        void u32(long v) {
            u8((int) (v & 0xFF));
            u8((int) ((v >>> 8) & 0xFF));
            u8((int) ((v >>> 16) & 0xFF));
            u8((int) ((v >>> 24) & 0xFF));
        }

        int size() {
            return out.size();
        }

        void write(byte[] data) {
            out.write(data, 0, data.length);
        }

        byte[] toBytes() {
            return out.toByteArray();
        }
    }
}
